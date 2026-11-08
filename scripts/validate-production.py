"""Reproducible isolated production-profile drill, explicit kubeconfig/context only.

Prerequisites: dedicated relayhub-validation Kind cluster with Calico, candidate
image loaded into it, Python cryptography/PyYAML and JDK keytool. Never uses the
default Kubernetes context. Generated secrets and reports stay under target/.
"""
import argparse
import base64
import concurrent.futures
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid

import yaml
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'target/readiness'
KUBECTL = str(OUT / 'bin/kubectl.exe') if os.name == 'nt' and (OUT / 'bin/kubectl.exe').is_file() else 'kubectl'
CTX = [KUBECTL, '--kubeconfig', str(OUT / 'kubeconfig'), '--context', 'kind-relayhub-validation']


def command(args, data=None, timeout=60, check=True):
    result = subprocess.run(args, input=data, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        # Never echo generated manifests, SQL or subprocess arguments with secrets.
        raise RuntimeError(f'{args[0]} failed ({result.returncode}): {result.stderr[-1200:]}')
    return result


def kube(*args, data=None, check=True, timeout=60):
    return command(CTX + list(args), data, timeout, check)


def apply(obj):
    kube('apply', '-f', '-', data=json.dumps(obj))


def wait(check, seconds=180):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            value = check()
            if value:
                return value
        except (OSError, RuntimeError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise AssertionError('Timed out waiting for validation state')


def sql(query):
    return kube('-n', 'relayhub', 'exec', '-i', 'postgres-0', '--', 'psql', '-U', 'relayhub',
                '-d', 'relayhub', '-At', '-v', 'ON_ERROR_STOP=1', data=query).stdout.strip()


def secret(name, data):
    apply({'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': name, 'namespace': 'relayhub'},
           'data': {key: base64.b64encode(value if isinstance(value, bytes) else value.encode()).decode()
                    for key, value in data.items()}})


def certificates(keytool):
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'RelayHub ephemeral validation CA')])
    now = datetime.now(timezone.utc)
    ca = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=5))
          .not_valid_after(now + timedelta(days=2)).add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
          .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), True)
          .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), False)
          .sign(ca_key, hashes.SHA256()))
    pem = ca.public_bytes(serialization.Encoding.PEM)
    (OUT / 'ca.pem').write_bytes(pem)
    trust = OUT / 'trust.p12'
    trust.unlink(missing_ok=True)
    command([keytool, '-importcert', '-noprompt', '-alias', 'fixture', '-file', str(OUT / 'ca.pem'),
             '-keystore', str(trust), '-storetype', 'PKCS12', '-storepass', 'validation-only'])
    secret('validation-trust', {'trust.p12': trust.read_bytes()})
    for identity, hosts in [('receiver', ['receiver.relayhub.svc.cluster.local']), ('edge', ['relayhub.local'])]:
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        sans = [x509.DNSName(host) for host in hosts]
        if identity == 'edge':
            sans.append(x509.IPAddress(ipaddress.ip_address('127.0.0.1')))
        leaf = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, hosts[0])]))
                .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=2))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), True)
                .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), False)
                .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), False)
                .add_extension(x509.SubjectAlternativeName(sans), False)
                .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), False)
                .sign(ca_key, hashes.SHA256()))
        secret(('relayhub-edge' if identity == 'edge' else identity) + '-tls', {'tls.crt': leaf.public_bytes(serialization.Encoding.PEM),
                                   'tls.key': key.private_bytes(serialization.Encoding.PEM,
                                        serialization.PrivateFormat.PKCS8, serialization.NoEncryption())})


FIXTURE = r'''
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib, hmac, json, ssl, threading, time
from verify_signature import verify_relayhub_signature
lock=threading.Lock()
seen={}; requests=0; alerts=[]; reject_until=0
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        with lock: body=json.dumps({'unique':len(seen),'requests':requests,'alerts':alerts}).encode()
        self.send_response(200); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        global requests,reject_until
        body=self.rfile.read(int(self.headers.get('Content-Length',0)))
        if self.path=='/fault':
            reject_until=time.monotonic()+int(body); self.send_response(200); self.end_headers(); return
        if self.path=='/alerts':
            with lock: alerts.append(json.loads(body))
            self.send_response(200); self.end_headers(); return
        if not verify_relayhub_signature(self.headers.get('X-RelayHub-Signature',''),body,'validation-hmac-fixture-only'):
            self.send_response(401); self.end_headers(); return
        if time.monotonic()<reject_until:
            self.send_response(503); self.end_headers(); return
        delivery=self.headers.get('X-RelayHub-Delivery','')
        if not delivery: self.send_response(400); self.end_headers(); return
        with lock:
            requests+=1
            digest=hashlib.sha256(body).hexdigest()
            if delivery in seen and seen[delivery]!=digest:
                self.send_response(409); self.end_headers(); return
            seen[delivery]=digest
        self.send_response(200); self.end_headers()
tls=ThreadingHTTPServer(('0.0.0.0',8443),Handler)
context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain('/tls/tls.crt','/tls/tls.key')
tls.socket=context.wrap_socket(tls.socket,server_side=True)
threading.Thread(target=tls.serve_forever,daemon=True).start()
ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
'''


def fixture():
    apply({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'validation-fixture', 'namespace': 'relayhub'},
           'data': {'server.py': FIXTURE, 'verify_signature.py': (ROOT / 'scripts/verify-signature.py').read_text(encoding='utf-8')}})
    pod = {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': 'receiver', 'namespace': 'relayhub'},
           'spec': {'replicas': 1, 'selector': {'matchLabels': {'app': 'receiver'}}, 'template': {
               'metadata': {'labels': {'app': 'receiver'}}, 'spec': {'automountServiceAccountToken': False,
                   'containers': [{'name': 'receiver', 'image': 'python:3.13-slim', 'imagePullPolicy': 'IfNotPresent',
                       'command': ['python', '/fixture/server.py'], 'resources': {'requests': {'memory': '32Mi', 'cpu': '10m'},
                           'limits': {'memory': '128Mi', 'cpu': '500m'}}, 'volumeMounts': [
                           {'name': 'code', 'mountPath': '/fixture'}, {'name': 'tls', 'mountPath': '/tls'}]}],
                   'volumes': [{'name': 'code', 'configMap': {'name': 'validation-fixture'}},
                               {'name': 'tls', 'secret': {'secretName': 'receiver-tls'}}]}}}}
    apply(pod)
    apply({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'receiver', 'namespace': 'relayhub'},
           'spec': {'selector': {'app': 'receiver'}, 'ports': [{'name': 'tls', 'port': 8443}, {'name': 'http', 'port': 8080}]}})


def receiver_state():
    result = kube('-n', 'relayhub', 'exec', 'deployment/receiver', '--', 'python', '-c',
                  "import urllib.request;print(urllib.request.urlopen('http://localhost:8080').read().decode())")
    return json.loads(result.stdout)


def policy(name, port):
    return {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy',
            'metadata': {'name': name, 'namespace': 'relayhub'}, 'spec': {
                'podSelector': {'matchLabels': {'app': 'relayhub'}}, 'policyTypes': ['Egress'],
                'egress': [{'to': [{'podSelector': {'matchLabels': {'app': 'receiver'}}}],
                            'ports': [{'protocol': 'TCP', 'port': port}]}]}}


def deploy(args):
    # Fail before creating anything on an unrelated or multi-node cluster.
    nodes = json.loads(kube('get', 'nodes', '-o', 'json').stdout)['items']
    assert [node['metadata']['name'] for node in nodes] == ['relayhub-validation-control-plane']
    if kube('get', 'namespace', 'relayhub', check=False).returncode == 0:
        raise RuntimeError('Disposable relayhub namespace already exists. Preserve reports and explicitly remove it before a fresh --deploy drill.')
    if (OUT / 'credentials.private.json').exists():
        raise RuntimeError('Preserve/move the previous private credential file before a fresh --deploy drill; refusing to overwrite it.')
    kube('apply', '-f', str(ROOT / 'deploy/k8s/00-namespace.yaml'))
    password = secrets.token_urlsafe(32)
    secret('relayhub-secrets', {'SPRING_DATASOURCE_USERNAME': 'relayhub', 'SPRING_DATASOURCE_PASSWORD': password})
    certificates(args.keytool)
    for obj in yaml.safe_load_all((ROOT / 'deploy/k8s/03-postgres.yaml').read_text()):
        if obj['kind'] == 'StatefulSet':
            obj['spec']['template']['spec']['containers'][0]['resources'] = {
                'requests': {'cpu': '100m', 'memory': '128Mi'}, 'limits': {'cpu': '1000m', 'memory': '512Mi'}}
        apply(obj)
    wait(lambda: kube('-n', 'relayhub', 'exec', 'postgres-0', '--', 'pg_isready', '-U', 'relayhub', check=False).returncode == 0)
    fixture()
    config = yaml.safe_load((ROOT / 'deploy/k8s/01-configmap.yaml').read_text())
    config['data'].update(KAFKA_ENABLED='false', RELAYHUB_WEBHOOK_ALLOWED_HOSTS='receiver.relayhub.svc.cluster.local',
                          RELAYHUB_WEBHOOK_ALLOW_PRIVATE_TARGETS='true', SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='8',
                          JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=/trust/trust.p12 -Djavax.net.ssl.trustStorePassword=validation-only')
    apply(config)
    apply({'apiVersion': 'batch/v1', 'kind': 'Job', 'metadata': {'name': 'relayhub-migrate', 'namespace': 'relayhub'},
           'spec': {'backoffLimit': 0, 'template': {'spec': {'restartPolicy': 'Never',
               'automountServiceAccountToken': False, 'containers': [{'name': 'migrate', 'image': args.image,
                   'imagePullPolicy': 'IfNotPresent', 'args': ['--migrate-only'],
                   'envFrom': [{'configMapRef': {'name': 'relayhub-config'}}, {'secretRef': {'name': 'relayhub-secrets'}}],
                   'env': [{'name': 'JAVA_TOOL_OPTIONS', 'value': ''}],
                   'resources': {'limits': {'memory': '512Mi', 'cpu': '1000m'}}}]}}}})
    wait(lambda: json.loads(kube('-n', 'relayhub', 'get', 'job', 'relayhub-migrate', '-o', 'json').stdout).get('status', {}).get('succeeded') == 1)
    assert sql('SELECT count(*) FROM api_keys;') == '0'
    for file in ['07-relayhub-service.yaml', '09-relayhub-egress.yaml', '10-relayhub-ingress-policy.yaml']:
        path = ROOT / 'deploy/k8s' / file
        for obj in yaml.safe_load_all(path.read_text()):
            if obj:
                apply(obj)
    apply(policy('validation-approved-receiver-tls', 8443))
    app = yaml.safe_load((ROOT / 'deploy/k8s/06-relayhub-deployment.yaml').read_text())
    container = app['spec']['template']['spec']['containers'][0]
    container['image'] = args.image
    container['volumeMounts'].append({'name': 'trust', 'mountPath': '/trust', 'readOnly': True})
    app['spec']['template']['spec']['volumes'].append({'name': 'trust', 'secret': {'secretName': 'validation-trust'}})
    apply(app)
    wait(lambda: sql('SELECT count(*) FROM flyway_schema_history;') == '3')
    spec = importlib.util.spec_from_file_location('bootstrap', ROOT / 'scripts/bootstrap-credentials.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    plan, keys = module.sql_plan('validation', 'Isolated production validation', True)
    private = OUT / 'credentials.private.json'
    module.write_private_json(private, keys)
    sql(plan)
    def application_ready():
        status = json.loads(kube('-n', 'relayhub', 'get', 'deployment', 'relayhub', '-o', 'json').stdout).get('status', {})
        return all(status.get(key) == 2 for key in ['readyReplicas', 'updatedReplicas', 'replicas'])
    wait(application_ready, 300)
    for obj in yaml.safe_load_all((ROOT / 'deploy/k8s/11-tls-edge.yaml').read_text()):
        if obj['kind'] == 'Deployment':
            obj['spec']['replicas'] = 1
        apply(obj)
    wait(lambda: json.loads(kube('-n', 'relayhub', 'get', 'deployment', 'relayhub-edge', '-o', 'json').stdout).get('status', {}).get('readyReplicas') == 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', default='relayhub:release-candidate')
    parser.add_argument('--keytool', default='keytool')
    parser.add_argument('--deploy', action='store_true')
    parser.add_argument('--seconds', type=int, default=600)
    parser.add_argument('--rate', type=int, default=5)
    args = parser.parse_args()
    if args.seconds <= 0 or args.rate <= 0:
        parser.error('--seconds and --rate must be positive')
    OUT.mkdir(parents=True, exist_ok=True)
    report = {'validation_id': 'RH-VAL-008', 'status': 'RUNNING', 'transport': 'direct', 'image': args.image,
              'scope': 'local single-node Kind, Calico, two application replicas; not hosted HA/capacity'}
    forward = None
    (OUT / 'kubernetes.json').write_text(json.dumps(report, indent=2)+'\n')
    try:
        if args.deploy:
            deploy(args)
        deployed = json.loads(kube('-n', 'relayhub', 'get', 'deployment', 'relayhub', '-o', 'json').stdout)
        assert deployed['spec']['template']['spec']['containers'][0]['image'] == args.image, 'Running deployment does not match the requested image'
        assert deployed.get('status', {}).get('readyReplicas') == 2, 'Wait for both application replicas before validating'
        keys = json.loads((OUT / 'credentials.private.json').read_text())
        context = ssl.create_default_context(cafile=str(OUT / 'ca.pem'))
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        log = (OUT / 'port-forward.log').open('w')
        forward = subprocess.Popen(CTX + ['-n', 'relayhub', 'port-forward', 'deployment/relayhub-edge', f'{port}:8443'], stdout=log, stderr=log)

        def api(path, body=None, role='admin', idem=None):
            headers = {'Content-Type': 'application/json', 'X-API-Key': keys[role] if role in keys else role}
            if idem: headers['Idempotency-Key'] = idem
            request = urllib.request.Request(f'https://127.0.0.1:{port}' + path, headers=headers,
                                              data=json.dumps(body).encode() if body is not None else None)
            try:
                with urllib.request.urlopen(request, context=context, timeout=40) as response:
                    return response.status, response.read().decode()
            except urllib.error.HTTPError as error:
                return error.code, error.read().decode()

        wait(lambda: api('/actuator/health/readiness')[0] == 200)
        assert api('/v3/api-docs')[0] == 404
        assert api('/api/v1/events', role='rlh_live_demo1234567890abcdef')[0] == 401
        assert api('/actuator/prometheus', role='observe')[0] == 200
        assert api('/api/v1/events', role='observe')[0] == 403
        assert api('/api/v1/subscriptions', {'targetUrl': 'http://receiver.relayhub.svc.cluster.local:8080/hook'})[0] == 400
        report['production_security'] = 'PASS'
        destination = 'https://receiver.relayhub.svc.cluster.local:8443/hook'
        existing = api('/api/v1/subscriptions')
        assert existing[0] == 200
        subscriptions = json.loads(existing[1])
        if subscriptions:
            assert len(subscriptions) == 1 and subscriptions[0]['targetUrl'] == destination, 'Use exactly one validation subscription'
        else:
            sub = api('/api/v1/subscriptions', {'targetUrl': destination,
                      'secret': 'validation-hmac-fixture-only', 'eventTypes': '*', 'maxRetries': 10, 'timeoutMs': 2000})
            assert sub[0] == 201, sub
        pod = json.loads(kube('-n', 'relayhub', 'get', 'pods', '-l', 'app=relayhub', '-o', 'json').stdout)['items'][0]['metadata']['name']
        def probe():
            return kube('-n', 'relayhub', 'exec', pod, '--', 'wget', '-q', '-T', '3', '-O', '-', 'http://receiver:8080/', check=False).returncode
        assert probe() != 0
        apply(policy('validation-http-control', 8080)); time.sleep(3)
        assert probe() == 0
        kube('-n', 'relayhub', 'delete', 'networkpolicy', 'validation-http-control'); time.sleep(3)
        assert probe() != 0
        report['calico_egress_deny_allow_deny'] = 'PASS'
        denied_ingress = kube('-n', 'relayhub', 'exec', 'deployment/receiver', '--', 'python', '-c',
            "import urllib.request;urllib.request.urlopen('http://relayhub:80/actuator/health',timeout=3)", check=False)
        assert denied_ingress.returncode != 0
        report['calico_ingress_denied_unapproved_source'] = 'PASS'
        start_count = int(sql('SELECT count(*) FROM events;'))
        receiver_before = receiver_state()
        run_id = uuid.uuid4().hex
        attempts = []; latencies = []; accepted = set(); duplicates = 0
        start = time.monotonic(); next_send = start; fault = False; restarted = False
        rpc = (OUT / 'rpc-attempts.jsonl').open('a')
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            pending = []
            def send(index):
                begin = time.monotonic()
                body = {'eventType': 'portfolio.validation', 'payload': {'run': run_id, 'sequence': index}}
                idem = run_id + '-' + str(index)
                try:
                    status, raw = api('/api/v1/events', body, 'ingest', idem)
                    result = {'run_id': run_id, 'index': index, 'status': status, 'seconds': time.monotonic() - begin}
                    if status == 201:
                        result['event_id'] = json.loads(raw)['eventId']
                    return result
                except (OSError, urllib.error.URLError) as error:
                    return {'run_id': run_id, 'index': index, 'status': 'UNCERTAIN', 'seconds': time.monotonic()-begin, 'error': type(error).__name__}
            index = 0
            while time.monotonic() - start < args.seconds:
                elapsed = time.monotonic() - start
                if not fault and elapsed >= args.seconds / 3:
                    kube('-n', 'relayhub', 'exec', 'deployment/receiver', '--', 'python', '-c',
                         "import urllib.request;urllib.request.urlopen(urllib.request.Request('http://localhost:8080/fault',data=b'12')).read()")
                    fault = True
                if not restarted and elapsed >= args.seconds * 2 / 3:
                    kube('-n', 'relayhub', 'delete', 'pod', pod, '--wait=false'); restarted = True
                if time.monotonic() >= next_send:
                    pending.append(pool.submit(send, index)); index += 1; next_send += 1 / args.rate
                else:
                    time.sleep(min(.05, next_send-time.monotonic()))
                # Bound concurrent work; overload becomes a recorded limitation, not unbounded client memory.
                if len(pending) >= 24:
                    pending[0].result()
                    time.sleep(.05)
                    pending = [future for future in pending if not future.done()] + [future for future in pending if future.done()]
                    # Harvest completed futures below without losing any individual attempt.
                ready = [future for future in pending if future.done()]
                for future in ready:
                    result = future.result(); attempts.append(result); rpc.write(json.dumps(result)+'\n'); rpc.flush()
                    pending.remove(future)
            for future in pending:
                result = future.result(); attempts.append(result); rpc.write(json.dumps(result)+'\n')
        rpc.close()
        # Retry uncertain outcomes with the exact original idempotency key/payload.
        for result in list(attempts):
            if result['status'] != 201:
                retried = send(result['index']); retried['retry'] = True; attempts.append(retried)
                with (OUT / 'rpc-attempts.jsonl').open('a') as output: output.write(json.dumps(retried)+'\n')
        assert all(any(r['index']==sequence and r['status']==201 for r in attempts) for sequence in range(index))
        accepted = {r['event_id'] for r in attempts if r['status']==201}
        assert len(accepted) == index
        # Explicit replay and payload mismatch across the shared two-replica service.
        replay = send(0); assert replay['event_id'] == next(r['event_id'] for r in attempts if r['index']==0)
        mismatch = api('/api/v1/events', {'eventType':'portfolio.validation','payload':{'changed':True}}, 'ingest', run_id+'-0')
        assert mismatch[0] == 422
        def drained():
            return sql("SELECT count(*) FROM webhook_deliveries WHERE status IN ('PENDING','RETRYING','IN_FLIGHT');") == '0'
        wait(drained, 240)
        final_count = int(sql('SELECT count(*) FROM events;'))
        success = int(sql("SELECT count(*) FROM webhook_deliveries WHERE status='SUCCESS';"))
        receiver = receiver_state()
        assert final_count - start_count == len(accepted)
        assert success == final_count and receiver['unique']-receiver_before['unique'] == len(accepted)
        readback = [json.loads(line) for line in sql("SELECT json_build_object('id',id,'payload',payload::json) FROM events WHERE payload::jsonb->>'run'='"+run_id+"';").splitlines()]
        assert {row['id'] for row in readback} == accepted
        assert {row['payload']['sequence'] for row in readback} == set(range(index))
        latencies = sorted(r['seconds'] for r in attempts if r['status']==201)
        report.update(status='PASS', run_id=run_id, requested_seconds=args.seconds, elapsed_seconds=round(time.monotonic()-start,3),
                      target_requests_per_second=args.rate, unique_accepted=len(accepted), rpc_attempts=len(attempts),
                      initial_status_counts={str(status): sum(r['status']==status for r in attempts if not r.get('retry')) for status in set(r['status'] for r in attempts)},
                      retries=sum(bool(r.get('retry')) for r in attempts), database_events=final_count,
                      successful_deliveries=success, receiver_unique=receiver['unique'], receiver_acceptance_attempts=receiver['requests'],
                      receiver_unique_this_run=receiver['unique']-receiver_before['unique'],
                      receiver_acceptance_attempts_this_run=receiver['requests']-receiver_before['requests'],
                      payload_sequence_readback='PASS',
                      latency_seconds={'p50': latencies[int((len(latencies)-1)*.5)], 'p95': latencies[int((len(latencies)-1)*.95)],
                                       'p99': latencies[int((len(latencies)-1)*.99)], 'max': max(latencies)},
                      receiver_503_seconds=12, application_pod_replacement=restarted, idempotency_replay_and_mismatch='PASS')
        print(json.dumps(report, indent=2))
    except Exception as error:
        report['status'] = 'FAIL'
        report['error'] = str(error)
        raise
    finally:
        (OUT / 'kubernetes.json').write_text(json.dumps(report, indent=2)+'\n')
        if forward:
            forward.terminate(); forward.wait(timeout=10)


if __name__ == '__main__':
    main()
