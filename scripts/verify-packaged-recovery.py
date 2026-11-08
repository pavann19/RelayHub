"""Isolated packaged-app restart check. Creates no persistent/user-owned volumes.

Build the desired image first, then run:
    python scripts/verify-packaged-recovery.py --image relayhub:fix-validation
"""
import argparse
import hashlib
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def docker(*args):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True).stdout.strip()


def wait_for(check, timeout=90):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (urllib.error.URLError, OSError, subprocess.CalledProcessError) as error:
            last = error
        time.sleep(0.3)
    raise AssertionError(f"Timed out waiting for validation state: {last}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--image", default="relayhub:fix-validation")
    parser.add_argument("--output", default="target/packaged-recovery.json")
    parser.add_argument("--soak-seconds", type=int, default=0, help="Bounded local correctness run; not a capacity benchmark")
    parser.add_argument("--jar", help="Mount a freshly verified JAR read-only into the runtime image")
    args = parser.parse_args()
    prefix = "relayhub-recovery-" + uuid.uuid4().hex[:10]
    network = prefix + "-net"
    database = prefix + "-db"
    receiver = prefix + "-receiver"
    first = prefix + "-first"
    second = prefix + "-second"
    containers = []
    started = time.monotonic()
    report = {"status": "FAIL", "transport": "direct", "image": args.image}
    jar = Path(args.jar).resolve() if args.jar else None
    if jar:
        if not jar.is_file():
            raise FileNotFoundError(jar)
        report.update(artifact_mode="read-only packaged JAR", jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())

    def run(name, *options):
        containers.append(name)
        docker("run", "-d", "--name", name, "--network", network, *options)

    def app(name, workers):
        mount = ["--mount", f"type=bind,source={jar},target=/app/relayhub.jar,readonly"] if jar else []
        run(name, *mount, "-p", "127.0.0.1::8080",
            "-e", "SPRING_DATASOURCE_URL=jdbc:postgresql://database:5432/recovery",
            "-e", "SPRING_DATASOURCE_USERNAME=recovery",
            "-e", "SPRING_DATASOURCE_PASSWORD=isolated-validation-only",
            "-e", "RELAYHUB_SEED_LOCATION=classpath:data.sql",
            "-e", "RELAYHUB_WEBHOOK_ALLOWED_HOSTS=receiver",
            "-e", "RELAYHUB_WEBHOOK_ALLOW_PRIVATE_TARGETS=true",
            "-e", f"RELAYHUB_WORKERS_ENABLED={str(workers).lower()}", args.image)
        mapped = docker("port", name, "8080/tcp").splitlines()[0]
        return "http://" + mapped

    def api(base, path, key=None, body=None, idem=None):
        headers = {"Content-Type": "application/json"}
        if key:
            headers["X-API-Key"] = key
        if idem:
            headers["Idempotency-Key"] = idem
        request = urllib.request.Request(base + path, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, json.load(response)

    def sql(query):
        return docker("exec", database, "psql", "-U", "recovery", "-d", "recovery", "-Atc", query)

    try:
        docker("network", "create", network)
        run(database, "--network-alias", "database", "-e", "POSTGRES_USER=recovery",
            "-e", "POSTGRES_PASSWORD=isolated-validation-only", "-e", "POSTGRES_DB=recovery", "postgres:16-alpine")
        wait_for(lambda: docker("exec", database, "pg_isready", "-U", "recovery", "-d", "recovery"))
        server = """
from http.server import BaseHTTPRequestHandler, HTTPServer
class Receiver(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        print('PAYLOAD ' + body.decode(), flush=True)
        print('ACCEPTED ' + self.headers.get('X-RelayHub-Delivery', ''), flush=True)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'ok')
HTTPServer(('0.0.0.0', 8080), Receiver).serve_forever()
"""
        run(receiver, "--network-alias", "receiver", "python:3.13-slim", "python", "-u", "-c", server)
        base = app(first, False)
        wait_for(lambda: api(base, "/actuator/health")[1].get("status") == "UP")
        admin = "rlh_admin_9876543210fedcba"
        ingest = "rlh_live_demo1234567890abcdef"
        status, _ = api(base, "/api/v1/subscriptions", admin,
                        {"targetUrl": "http://receiver:8080/webhook", "eventTypes": "restart.test"})
        assert status == 201
        status, event = api(base, "/api/v1/events", ingest,
                            {"eventType": "restart.test", "payload": {"value": 1}}, prefix)
        assert status == 201
        event_id = event["eventId"]
        assert sql("SELECT count(*) FROM event_outbox WHERE published_at IS NULL") == "1"
        assert sql("SELECT count(*) FROM webhook_deliveries") == "0"
        # Simulate abrupt loss after the accepted response, before publication.
        docker("kill", first)
        base = app(second, True)
        wait_for(lambda: api(base, "/actuator/health")[1].get("status") == "UP")
        wait_for(lambda: api(base, "/api/v1/events/" + event_id, ingest)[1]["status"] == "DELIVERED")
        assert sql("SELECT count(*) FROM event_outbox WHERE published_at IS NULL") == "0"
        assert sql("SELECT count(*) FROM webhook_delivery_attempts") == "1"
        accepted = [line for line in docker("logs", receiver).splitlines() if line.startswith("ACCEPTED ")]
        assert len(accepted) == 1, accepted
        assert 'PAYLOAD {"value":1}' in docker("logs", receiver).splitlines()
        status, duplicate = api(base, "/api/v1/events", ingest,
                                {"eventType": "restart.test", "payload": {"value": 1}}, prefix)
        assert status == 201 and duplicate["eventId"] == event_id
        expected_attempts = 1
        if args.soak_seconds > 0:
            deadline = time.monotonic() + args.soak_seconds
            accepted_events = []
            while time.monotonic() < deadline:
                sequence = len(accepted_events)
                status, accepted_event = api(base, "/api/v1/events", ingest,
                                             {"eventType": "restart.test", "payload": {"sequence": sequence}},
                                             f"{prefix}-soak-{sequence}")
                assert status == 201
                accepted_events.append(accepted_event["eventId"])
                time.sleep(0.2)
            expected_attempts += len(accepted_events)
            wait_for(lambda: int(sql("SELECT count(*) FROM events WHERE status='DELIVERED'")) == expected_attempts)
            assert int(sql("SELECT count(*) FROM webhook_delivery_attempts")) == expected_attempts
            accepted_lines = [line for line in docker("logs", receiver).splitlines() if line.startswith("ACCEPTED ")]
            assert len(accepted_lines) == expected_attempts
            assert len(set(accepted_lines)) == expected_attempts
            report["bounded_local_correctness_run"] = {"duration_seconds": args.soak_seconds,
                "additional_accepted_events": len(accepted_events), "delivered": len(accepted_events),
                "duplicate_receiver_acceptances": 0, "capacity_claim": False}
        # Verify a real logical backup can be restored into another disposable database.
        dump = subprocess.run(["docker", "exec", database, "pg_dump", "-U", "recovery", "-d", "recovery"],
                              check=True, capture_output=True).stdout
        docker("exec", database, "createdb", "-U", "recovery", "restored")
        subprocess.run(["docker", "exec", "-i", database, "psql", "-v", "ON_ERROR_STOP=1", "-U", "recovery", "-d", "restored"],
                       input=dump, check=True, capture_output=True)
        restored_attempts = docker("exec", database, "psql", "-U", "recovery", "-d", "restored", "-Atc",
                                   "SELECT count(*) FROM webhook_delivery_attempts")
        assert int(restored_attempts) == expected_attempts
        assert docker("exec", database, "psql", "-U", "recovery", "-d", "restored", "-Atc",
                      "SELECT count(*) FROM flyway_schema_history WHERE success") == "3"
        report.update(status="PASS", backup_restore_verified=True, accepted_before_process_kill=True, pending_outbox_before_kill=1,
                      delivered_after_replacement=True, committed_attempts=expected_attempts, receiver_acceptances=expected_attempts,
                      idempotent_response_survived_restart=True, original_payload_preserved=True)
    finally:
        for name in reversed(containers):
            subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True)
        subprocess.run(["docker", "network", "rm", network], capture_output=True)
        report["elapsed_seconds"] = round(time.monotonic() - started, 2)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
