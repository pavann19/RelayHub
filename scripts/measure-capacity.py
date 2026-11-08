"""Bounded local workload ladder. Requires a completed isolated production drill.

Records every RPC, database readback, backlog drain and end-to-end delivery age.
This measures the stated local topology; it does not estimate hosted capacity.
"""
import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid

spec = importlib.util.spec_from_file_location('production', Path(__file__).with_name('validate-production.py'))
p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)


def percentile(values, quantile):
    ordered = sorted(values)
    return round(ordered[int((len(ordered)-1)*quantile)], 6) if ordered else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rates', default='5,10,20')
    parser.add_argument('--seconds', type=int, default=60)
    args = parser.parse_args()
    report = {'validation_id': 'RH-VAL-009', 'status': 'RUNNING', 'phases': [],
              'scope': 'Local single-node Kind/Calico; two application replicas; direct transport; TLS edge and receiver',
              'acceptance': 'All unique requests acknowledged and read back; all deliveries drain within 180 seconds; no duplicate receiver acceptances',
              'latency_target': 'Measured, no prior service-level commitment'}
    keys = json.loads((p.OUT / 'credentials.private.json').read_text())
    context = ssl.create_default_context(cafile=str(p.OUT / 'ca.pem'))
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    log = (p.OUT / 'capacity-port-forward.log').open('w')
    forward = subprocess.Popen(p.CTX + ['-n','relayhub','port-forward','deployment/relayhub-edge',f'{port}:8443'], stdout=log, stderr=log)
    try:
        p.wait(lambda: 'Forwarding from' in (p.OUT / 'capacity-port-forward.log').read_text())
        for rate in [int(value) for value in args.rates.split(',')]:
            run = uuid.uuid4().hex
            before = p.receiver_state()
            attempts = []
            def send(sequence):
                begin = time.monotonic()
                request = urllib.request.Request(f'https://127.0.0.1:{port}/api/v1/events',
                    data=json.dumps({'eventType':'portfolio.capacity','payload':{'run':run,'sequence':sequence}}).encode(),
                    headers={'Content-Type':'application/json','X-API-Key':keys['ingest'],'Idempotency-Key':run+'-'+str(sequence)})
                result = {'run_id':run,'sequence':sequence}
                try:
                    with urllib.request.urlopen(request, context=context, timeout=30) as response:
                        result.update(status=response.status,event_id=json.load(response)['eventId'])
                except urllib.error.HTTPError as error:
                    result.update(status=error.code)
                except (OSError,urllib.error.URLError) as error:
                    result.update(status='UNCERTAIN',error=type(error).__name__)
                result['seconds'] = time.monotonic()-begin
                return result
            begin = time.monotonic()
            with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
                pending = set()
                for sequence in range(rate*args.seconds):
                    # Bounded open-loop driver: stalls are reflected in achieved duration/rate.
                    while len(pending) >= 24:
                        done, pending = concurrent.futures.wait(pending, return_when=concurrent.futures.FIRST_COMPLETED)
                        attempts.extend(future.result() for future in done)
                    delay = begin+sequence/rate-time.monotonic()
                    if delay>0: time.sleep(delay)
                    pending.add(pool.submit(send,sequence))
                attempts.extend(future.result() for future in concurrent.futures.as_completed(pending))
            ingest_seconds = time.monotonic()-begin
            with (p.OUT / 'capacity-rpc.jsonl').open('a') as output:
                output.writelines(json.dumps(attempt)+'\n' for attempt in attempts)
            failures = [attempt for attempt in attempts if attempt['status']!=201]
            # Reconcile uncertain outcomes via exact-key retry, preserving each individual attempt.
            for original in failures:
                retried = send(original['sequence']); retried['retry']=True; attempts.append(retried)
                with (p.OUT / 'capacity-rpc.jsonl').open('a') as output: output.write(json.dumps(retried)+'\n')
            accepted = {a['event_id'] for a in attempts if a['status']==201}
            expected = rate*args.seconds
            assert len(accepted)==expected
            readback = [json.loads(line) for line in p.sql("SELECT json_build_object('id',id,'payload',payload::json) FROM events WHERE payload::jsonb->>'run'='"+run+"';").splitlines()]
            assert {row['id'] for row in readback}==accepted
            assert {row['payload']['sequence'] for row in readback}==set(range(expected))
            backlog = int(p.sql("SELECT count(*) FROM webhook_deliveries WHERE status IN ('PENDING','RETRYING','IN_FLIGHT');"))
            drain_start = time.monotonic()
            p.wait(lambda: int(p.sql("SELECT count(*) FROM webhook_deliveries WHERE status IN ('PENDING','RETRYING','IN_FLIGHT');"))==0,180)
            after = p.receiver_state()
            assert after['unique']-before['unique']==expected
            assert after['requests']-before['requests']==expected
            delivered = int(p.sql("SELECT count(*) FROM webhook_deliveries d JOIN events e ON e.id=d.event_id WHERE e.payload::jsonb->>'run'='"+run+"' AND d.status='SUCCESS';"))
            assert delivered==expected
            ages = [float(value) for value in p.sql("SELECT EXTRACT(EPOCH FROM(d.completed_at-e.created_at)) FROM webhook_deliveries d JOIN events e ON e.id=d.event_id WHERE e.payload::jsonb->>'run'='"+run+"';").splitlines()]
            latencies = [a['seconds'] for a in attempts if a['status']==201]
            phase = {'run_id':run,'target_rps':rate,'requested_seconds':args.seconds,'ingestion_elapsed_seconds':round(ingest_seconds,3),
                     'achieved_acknowledgements_per_second':round(expected/ingest_seconds,3), 'unique_events':expected,
                     'first_attempt_non_201':len(failures),'backlog_at_ingestion_stop':backlog,
                     'drain_seconds':round(time.monotonic()-drain_start,3),'delivery_successes':delivered,
                     'ingest_latency_seconds':{name:percentile(latencies,q) for name,q in [('p50',.5),('p95',.95),('p99',.99)]},
                     'end_to_end_delivery_seconds':{name:percentile(ages,q) for name,q in [('p50',.5),('p95',.95),('p99',.99)]},
                     'receiver_duplicates_observed':0,'status':'PASS'}
            report['phases'].append(phase)
            (p.OUT / 'capacity.json').write_text(json.dumps(report,indent=2)+'\n')
            print(json.dumps(phase),flush=True)
        report['status']='PASS'
    except Exception as error:
        report['status']='FAIL'
        report['error']=str(error)
        raise
    finally:
        (p.OUT / 'capacity.json').write_text(json.dumps(report,indent=2)+'\n')
        forward.terminate(); forward.wait(timeout=10); log.close()


if __name__=='__main__':
    main()
