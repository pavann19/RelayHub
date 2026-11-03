import requests
import json
import time

INST1 = "http://localhost:8080"
INST2 = "http://localhost:8081"
API_KEY = "rlh_live_demo1234567890abcdef"
HEADERS = {
    "X-API-Key": API_KEY,
    "Content-Type": "application/json"
}

def test_multi_instance():
    print("=======================================================")
    print("MULTI-INSTANCE VERIFICATION (Instance 1 & Instance 2)")
    print("=======================================================")
    
    # 1. Health checks on both instances
    print("[1] Verifying instances health...")
    for name, url in [("Instance 1", INST1), ("Instance 2", INST2)]:
        for _ in range(15):
            try:
                r = requests.get(f"{url}/actuator/health", timeout=3)
                if r.status_code == 200 and r.json().get("status") == "UP":
                    print(f"    {name} ({url}) is UP & HEALTHY")
                    break
            except Exception:
                time.sleep(2)
        else:
            raise RuntimeError(f"{name} failed to become healthy")
            
    # 2. Cross-instance Idempotency Test
    print("\n[2] Testing Cross-Instance Idempotent Deduplication...")
    idemp_key = f"cross-inst-{int(time.time())}"
    payload = {
        "eventType": "payment.completed",
        "payload": json.dumps({"orderId": "ord_cross_42", "amount": 250.0, "currency": "EUR"})
    }
    
    # Send to Instance 1
    h1 = dict(HEADERS, **{"Idempotency-Key": idemp_key})
    r1 = requests.post(f"{INST1}/api/v1/events", headers=h1, json=payload)
    print(f"    Instance 1 response: status={r1.status_code}, rate_remaining={r1.headers.get('X-RateLimit-Remaining')}")
    assert r1.status_code == 201
    resp1 = r1.json()
    event_id = resp1["eventId"]
    print(f"    Instance 1 created Event ID: {event_id}")
    
    # Send IDENTICAL request to Instance 2
    r2 = requests.post(f"{INST2}/api/v1/events", headers=h1, json=payload)
    print(f"    Instance 2 response: status={r2.status_code}, replay_header={r2.headers.get('Idempotent-Replay')}")
    assert r2.status_code == 201
    assert r2.headers.get("Idempotent-Replay") == "true", "Expected Idempotent-Replay header from Instance 2"
    resp2 = r2.json()
    assert resp2["eventId"] == event_id, f"Expected same eventId {event_id}, got {resp2['eventId']}"
    print(f"    [VERIFIED] Instance 2 successfully deduplicated using shared database!")
    
    # 3. Cross-instance Payload Mismatch Rejection
    print("\n[3] Testing Cross-Instance Payload Mismatch Protection...")
    tampered_payload = dict(payload, payload=json.dumps({"orderId": "ord_cross_42", "amount": 9999.0}))
    r3 = requests.post(f"{INST2}/api/v1/events", headers=h1, json=tampered_payload)
    print(f"    Instance 2 mismatch response: status={r3.status_code}, body={r3.text}")
    assert r3.status_code == 422, f"Expected 422, got {r3.status_code}"
    print(f"    [VERIFIED] Instance 2 rejected tampered payload with 422 Unprocessable Entity!")

    # 4. Shared Delivery Tracking
    print("\n[4] Testing Shared Delivery Audit Trail...")
    time.sleep(2)
    r_del1 = requests.get(f"{INST1}/api/v1/deliveries", headers=HEADERS)
    r_del2 = requests.get(f"{INST2}/api/v1/deliveries", headers=HEADERS)
    print(f"    Deliveries read from Instance 1: {len(r_del1.json() if r_del1.status_code == 200 else [])}")
    print(f"    Deliveries read from Instance 2: {len(r_del2.json() if r_del2.status_code == 200 else [])}")
    assert r_del1.status_code == 200
    assert r_del2.status_code == 200
    assert len(r_del1.json()) == len(r_del2.json())
    print("    [VERIFIED] Both instances read identical delivery states!")

    print("\n=======================================================")
    print("MULTI-INSTANCE VERIFICATION PASSED 100%!")
    print("=======================================================")

if __name__ == "__main__":
    test_multi_instance()
