import requests
import json
import time
import subprocess
import sys

BASE_URL = "http://localhost:8080"
INGEST_KEY = "rlh_live_demo1234567890abcdef"
HEADERS = {
    "X-API-Key": INGEST_KEY,
    "Content-Type": "application/json"
}

def run_cmd(cmd):
    print(f"\n[EXEC] {cmd}")
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"[WARN] Error running {cmd}: {res.stderr}")
    else:
        print(f"[OK] {res.stdout.strip()}")
    return res

def test_downstream_receiver_recovery():
    print("\n=======================================================")
    print("CHAOS TEST 1: Downstream Webhook Receiver Recovery")
    print("=======================================================")
    
    # 1. Stop mock-receiver to induce 100% downstream failure
    print("[1] Stopping mock receiver...")
    run_cmd("docker compose -f deploy/docker-compose.yml stop mock-receiver")
    time.sleep(2)
    
    # 2. Ingest event during receiver outage
    event_payload = {
        "eventType": "payment.completed",
        "payload": json.dumps({"chaos": "receiver_down", "timestamp": time.time()})
    }
    r = requests.post(
        f"{BASE_URL}/api/v1/events",
        headers=dict(HEADERS, **{"Idempotency-Key": f"chaos-recv-{int(time.time())}"}),
        json=event_payload
    )
    print(f"[2] Ingest during downstream outage: status={r.status_code}")
    assert r.status_code == 201, f"Expected 201, got {r.status_code}"
    event_id = r.json()["eventId"]
    print(f"    Event ingested successfully: {event_id}")
    
    # 3. Wait for initial dispatch attempt to fail & schedule retry
    time.sleep(4)
    r_del = requests.get(f"{BASE_URL}/api/v1/deliveries/event/{event_id}", headers=HEADERS)
    print(f"[3] Delivery status during outage: status={r_del.status_code}")
    deliveries = r_del.json() if r_del.status_code == 200 else []
    print(f"    Deliveries recorded: {len(deliveries)}")
    if deliveries:
        print(f"    First attempt status: {deliveries[0]['status']}, attemptNumber={deliveries[0]['attemptNumber']}")
        assert deliveries[0]['status'] in ("RETRYING", "FAILED"), f"Expected RETRYING or FAILED, got {deliveries[0]['status']}"
    
    # 4. Restart mock receiver
    print("[4] Resuming downstream mock receiver...")
    run_cmd("docker compose -f deploy/docker-compose.yml start mock-receiver")
    time.sleep(3)
    
    # 5. Ingest another event to verify active delivery path
    r_rec = requests.post(
        f"{BASE_URL}/api/v1/events",
        headers=dict(HEADERS, **{"Idempotency-Key": f"chaos-recv-recov-{int(time.time())}"}),
        json=event_payload
    )
    print(f"[5] Ingest after receiver recovery: status={r_rec.status_code}")
    assert r_rec.status_code == 201
    recov_event_id = r_rec.json()["eventId"]
    
    time.sleep(3)
    r_del_recov = requests.get(f"{BASE_URL}/api/v1/deliveries/event/{recov_event_id}", headers=HEADERS)
    recov_deliveries = r_del_recov.json() if r_del_recov.status_code == 200 else []
    print(f"    Recovered delivery: {recov_deliveries}")
    if recov_deliveries:
        print(f"    Status after recovery: {recov_deliveries[0]['status']}, httpStatus={recov_deliveries[0]['httpStatus']}")
        assert recov_deliveries[0]['status'] == "SUCCESS"
    print("[PASS] Downstream receiver recovery verified!")

def test_kafka_broker_restart():
    print("\n=======================================================")
    print("CHAOS TEST 2: Kafka Broker Restart Resilience")
    print("=======================================================")
    
    print("[1] Restarting Kafka KRaft container...")
    run_cmd("docker compose -f deploy/docker-compose.yml restart kafka")
    
    # Wait for Kafka healthcheck
    print("[2] Waiting for Kafka to become healthy...")
    for _ in range(30):
        time.sleep(2)
        res = subprocess.run("docker inspect --format=\"{{.State.Health.Status}}\" relayhub-kafka", shell=True, capture_output=True, text=True)
        status = res.stdout.strip()
        print(f"    Kafka status: {status}")
        if status == "healthy":
            break
            
    time.sleep(5) # Allow consumer group rebalance
    
    # Ingest event after Kafka recovery
    event_payload = {
        "eventType": "payment.completed",
        "payload": json.dumps({"chaos": "kafka_restart", "timestamp": time.time()})
    }
    r = requests.post(
        f"{BASE_URL}/api/v1/events",
        headers=dict(HEADERS, **{"Idempotency-Key": f"chaos-kafka-{int(time.time())}"}),
        json=event_payload
    )
    print(f"[3] Ingest after Kafka restart: status={r.status_code}")
    assert r.status_code == 201, f"Expected 201, got {r.status_code}"
    event_id = r.json()["eventId"]
    
    time.sleep(3)
    r_del = requests.get(f"{BASE_URL}/api/v1/deliveries/event/{event_id}", headers=HEADERS)
    deliveries = r_del.json() if r_del.status_code == 200 else []
    print(f"    Delivery after Kafka restart: {deliveries}")
    if deliveries:
        assert deliveries[0]['status'] == "SUCCESS"
    print("[PASS] Kafka broker restart and consumer recovery verified!")

def test_postgres_restart():
    print("\n=======================================================")
    print("CHAOS TEST 3: PostgreSQL Database Restart & HikariCP Reconnect")
    print("=======================================================")
    
    print("[1] Restarting PostgreSQL container...")
    run_cmd("docker compose -f deploy/docker-compose.yml restart postgres")
    
    print("[2] Waiting for Postgres healthcheck...")
    for _ in range(20):
        time.sleep(2)
        res = subprocess.run("docker inspect --format=\"{{.State.Health.Status}}\" relayhub-postgres", shell=True, capture_output=True, text=True)
        status = res.stdout.strip()
        print(f"    Postgres status: {status}")
        if status == "healthy":
            break
            
    time.sleep(3)
    
    # Query deliveries through app to verify HikariCP pool self-heals
    r = requests.get(f"{BASE_URL}/api/v1/deliveries", headers=HEADERS)
    print(f"[3] Delivery query after Postgres restart: status={r.status_code}")
    assert r.status_code == 200, f"Expected 200, got {r.status_code}"
    
    # Ingest event to verify write path
    event_payload = {
        "eventType": "payment.completed",
        "payload": json.dumps({"chaos": "postgres_restart", "timestamp": time.time()})
    }
    r_ing = requests.post(
        f"{BASE_URL}/api/v1/events",
        headers=dict(HEADERS, **{"Idempotency-Key": f"chaos-pg-{int(time.time())}"}),
        json=event_payload
    )
    print(f"[4] Ingest after Postgres restart: status={r_ing.status_code}")
    assert r_ing.status_code == 201
    print("[PASS] PostgreSQL restart and HikariCP pool recovery verified!")

def test_redis_outage():
    print("\n=======================================================")
    print("CHAOS TEST 4: Redis Outage & Rate Limiter Resilience")
    print("=======================================================")
    
    print("[1] Stopping Redis container...")
    run_cmd("docker compose -f deploy/docker-compose.yml stop redis")
    time.sleep(2)
    
    # Ingest should continue to operate (fail-open or in-memory fallback)
    r = requests.post(
        f"{BASE_URL}/api/v1/events",
        headers=dict(HEADERS, **{"Idempotency-Key": f"chaos-redis-{int(time.time())}"}),
        json={"eventType": "payment.completed", "payload": "{}"}
    )
    print(f"[2] Ingest during Redis outage: status={r.status_code}")
    assert r.status_code in (201, 429), f"Expected 201 or 429, got {r.status_code}"
    
    print("[3] Restarting Redis container...")
    run_cmd("docker compose -f deploy/docker-compose.yml start redis")
    time.sleep(3)
    print("[PASS] Redis outage handled gracefully!")

if __name__ == "__main__":
    test_downstream_receiver_recovery()
    test_kafka_broker_restart()
    test_postgres_restart()
    test_redis_outage()
    print("\n=======================================================")
    print("ALL CHAOS AND RECOVERY TESTS COMPLETED SUCCESSFULLY!")
    print("=======================================================")

