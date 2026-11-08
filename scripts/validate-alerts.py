"""Actual Prometheus -> Alertmanager -> local fixture receipt, isolated cluster only."""
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location('production', Path(__file__).with_name('validate-production.py'))
p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)


def deploy(name, image, args, mounts, volumes, port):
    p.apply({'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': name, 'namespace': 'relayhub'},
             'spec': {'replicas': 1, 'selector': {'matchLabels': {'app': name}}, 'template': {
                 'metadata': {'labels': {'app': name}}, 'spec': {'automountServiceAccountToken': False, 'containers': [{
                     'name': name, 'image': image, 'args': args, 'volumeMounts': mounts,
                     'resources': {'requests': {'memory': '32Mi', 'cpu': '10m'}, 'limits': {'memory': '256Mi', 'cpu': '500m'}}}],
                     'volumes': volumes}}}})
    p.apply({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': name, 'namespace': 'relayhub'},
             'spec': {'selector': {'app': name}, 'ports': [{'port': port}]}})


def main():
    report = {'validation_id': 'RH-VAL-008-alerts', 'status': 'FAIL'}
    inserted = False
    try:
        keys = json.loads((p.OUT / 'credentials.private.json').read_text())
        p.secret('observe-token', {'token': keys['observe']})
        p.apply({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'relayhub-metrics', 'namespace': 'relayhub'},
                 'spec': {'clusterIP': 'None', 'selector': {'app': 'relayhub'}, 'ports': [{'port': 8080}]}})
        p.apply({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'production-prometheus', 'namespace': 'relayhub'},
                 'data': {name: (p.ROOT / 'deploy/prometheus' / name).read_text() for name in ['production.yml', 'production-rules.yml']}})
        # This destination is an isolated test fixture, never a person or external integration.
        am = {'route': {'receiver': 'local-fixture', 'group_by': ['alertname'], 'group_wait': '1s',
                        'group_interval': '5s', 'repeat_interval': '1h'},
              'receivers': [{'name': 'local-fixture', 'webhook_configs': [{'url': 'http://receiver:8080/alerts', 'send_resolved': True}]}]}
        p.apply({'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'validation-alertmanager', 'namespace': 'relayhub'},
                 'data': {'alertmanager.yml': json.dumps(am)}})
        deploy('alertmanager', 'prom/alertmanager:v0.27.0', ['--config.file=/etc/alertmanager/alertmanager.yml', '--storage.path=/data', '--cluster.listen-address='],
               [{'name': 'config', 'mountPath': '/etc/alertmanager'}, {'name': 'data', 'mountPath': '/data'}],
               [{'name': 'config', 'configMap': {'name': 'validation-alertmanager'}}, {'name': 'data', 'emptyDir': {}}], 9093)
        deploy('prometheus', 'prom/prometheus:v2.53.0', ['--config.file=/etc/prometheus/production.yml', '--storage.tsdb.path=/data', '--storage.tsdb.retention.time=2h'],
               [{'name': 'config', 'mountPath': '/etc/prometheus'}, {'name': 'token', 'mountPath': '/etc/relayhub-observe'}, {'name': 'data', 'mountPath': '/data'}],
               [{'name': 'config', 'configMap': {'name': 'production-prometheus'}}, {'name': 'token', 'secret': {'secretName': 'observe-token'}}, {'name': 'data', 'emptyDir': {}}], 9090)
        def query(expression):
            script = "import urllib.request,urllib.parse;print(urllib.request.urlopen('http://prometheus:9090/api/v1/query?query='+urllib.parse.quote(" + repr(expression) + ")).read().decode())"
            return json.loads(p.kube('-n', 'relayhub', 'exec', 'deployment/receiver', '--', 'python', '-c', script).stdout)['data']['result']
        p.wait(lambda: len(query('up{job="relayhub"} == 1')) == 2)
        report['scraped_application_replicas'] = 2
        # Retained poison record in this disposable database; no production mutation.
        p.sql("INSERT INTO kafka_quarantine(topic,partition_id,record_offset,payload,error_type) VALUES ('validation',0,0,'{}','controlled alert drill');")
        inserted = True
        begin = time.monotonic()
        p.wait(lambda: query('ALERTS{alertname="RelayHubQuarantinedWork",alertstate="firing"}'), 120)
        p.wait(lambda: any(alert.get('status') == 'firing' and any(a['labels'].get('alertname') == 'RelayHubQuarantinedWork' for a in alert['alerts'])
                           for alert in p.receiver_state()['alerts']), 120)
        report['firing_notification_received_seconds'] = round(time.monotonic()-begin, 3)
        p.sql("DELETE FROM kafka_quarantine WHERE topic='validation' AND partition_id=0 AND record_offset=0;")
        inserted = False
        p.wait(lambda: any(alert.get('status') == 'resolved' and any(a['labels'].get('alertname') == 'RelayHubQuarantinedWork' for a in alert['alerts'])
                           for alert in p.receiver_state()['alerts']), 120)
        report.update(status='PASS', resolved_notification_received=True, credentials='restricted OBSERVE key mounted from Secret')
        print(json.dumps(report, indent=2))
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        if inserted:
            p.sql("DELETE FROM kafka_quarantine WHERE topic='validation' AND partition_id=0 AND record_offset=0;")
        (p.OUT / 'alerts.json').write_text(json.dumps(report, indent=2)+'\n')


if __name__ == '__main__':
    main()
