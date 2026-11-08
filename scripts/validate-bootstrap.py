"""Real packaged migration, fail-closed demo-key guard and private bootstrap drill.

Creates a disposable Docker network/database; never uses user volumes.
"""
import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'target/readiness'


def run(*args, data=None, check=True):
    result=subprocess.run(list(args),input=data,text=True,capture_output=True,timeout=120)
    if check and result.returncode:
        raise RuntimeError(f'{args[0]} failed; inspect private local logs, no credentials are echoed')
    return result


def main():
    image=sys.argv[1] if len(sys.argv)>1 else 'relayhub:release-candidate'
    prefix='relayhub-bootstrap-'+uuid.uuid4().hex[:10]
    network=prefix+'-net'; database=prefix+'-db'
    password=secrets.token_urlsafe(32)
    report={'validation_id':'RH-VAL-010-bootstrap','status':'FAIL','image':image}
    try:
        run('docker','network','create',network)
        run('docker','run','-d','--name',database,'--network',network,'--network-alias','database',
            '--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=256m',
            '-e','POSTGRES_USER=relayhub','-e','POSTGRES_DB=relayhub','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        for _ in range(60):
            if run('docker','exec',database,'pg_isready','-U','relayhub',check=False).returncode==0: break
            time.sleep(.5)
        else: raise AssertionError('Database did not become ready')
        def sql(query):
            return run('docker','exec','-i',database,'psql','-U','relayhub','-d','relayhub','-At','-v','ON_ERROR_STOP=1',data=query).stdout.strip()
        def migrate(suffix):
            name=prefix+'-'+suffix
            result=run('docker','run','--rm','--name',name,'--network',network,'--memory','512m',
                '--read-only','--tmpfs','/tmp:rw,nosuid,size=128m',
                '-e','SPRING_PROFILES_ACTIVE=production','-e','SPRING_DATASOURCE_URL=jdbc:postgresql://database:5432/relayhub',
                '-e','SPRING_DATASOURCE_USERNAME=relayhub','-e','SPRING_DATASOURCE_PASSWORD='+password,
                '-e','RELAYHUB_WEBHOOK_ALLOWED_HOSTS=receiver.example.org','-e','RELAYHUB_WORKERS_ENABLED=true',image,'--migrate-only',check=False)
            (OUT/(suffix+'-migration.log')).write_text(result.stdout+result.stderr)
            return result
        assert migrate('fresh').returncode==0
        assert sql('SELECT count(*) FROM flyway_schema_history;')=='3'
        assert sql('SELECT count(*) FROM api_keys;')=='0'
        report['fresh_migration_no_seed']='PASS'
        sql((ROOT/'src/main/resources/data.sql').read_text())
        failed=migrate('unsafe-demo')
        assert failed.returncode!=0 and 'Revoke the published demo API keys' in failed.stdout+failed.stderr
        report['published_demo_key_startup_rejected']='PASS'
        bootstrap=[sys.executable,str(ROOT/'scripts/bootstrap-credentials.py'),'--container',database,
                   '--database','relayhub','--db-user','relayhub','--tenant','tenant_default','--revoke-demo-keys']
        preview=run(*bootstrap)
        assert json.loads(preview.stdout)['writes'] is False
        assert sql('SELECT count(*) FROM api_keys;')=='2'
        private=OUT/(prefix+'.private.json')
        result=run(*bootstrap,'--apply','--output',str(private))
        keys=json.loads(private.read_text())['keys']
        assert all(raw not in result.stdout for raw in keys.values())
        assert sql('SELECT count(*) FROM api_keys WHERE is_active=true;')=='3'
        assert sql("SELECT count(*) FROM api_keys WHERE id IN ('key_demo_ingest','key_demo_admin') AND is_active=false;")=='2'
        assert migrate('private-bootstrap').returncode==0
        duplicate=run(*bootstrap,'--apply','--output',str(private),check=False)
        assert duplicate.returncode!=0
        assert sql('SELECT count(*) FROM api_keys;')=='5'
        report.update(status='PASS',preview_read_only=True,private_roles=['ADMIN','INGEST','OBSERVE'],
                      demo_keys_explicitly_revoked=2,overwrite_refused_without_database_writes=True,
                      migrations_idempotent_after_private_bootstrap=True,migration_mode='non-web; exited successfully with workers environment enabled')
        print(json.dumps(report,indent=2))
    except Exception as error:
        report['error']=str(error);raise
    finally:
        for suffix in ['fresh','unsafe-demo','private-bootstrap']:
            run('docker','rm','-f',prefix+'-'+suffix,check=False)
        run('docker','rm','-f',database,check=False)
        run('docker','network','rm',network,check=False)
        (OUT/'bootstrap.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':
    main()
