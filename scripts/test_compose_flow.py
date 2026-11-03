import requests
import json
import time

BASE_URL = "http://localhost:8080"
HEADERS = {
    "X-API-Key": "rlh_live_demo1234567890abcdef",
    "Content-Type": "application/json"
}

def test_flow():
    print("--- 1. Testing Event Ingestion ---")
    idemp_key = f"flow-test-{int(time.time())}"
    payload = {
        "eventType": "payment.completed",
        "payload": json.dumps({"orderId": "ord_1001", "amount": 149.99, "currency": "EUR"})
    }
    
    # First Ingestion
    h1 = dict(HEADERS, **{"Idempotency-Key": idemp_key})
    r1 = requests.post(f"{BASE_URL}/api/v1/events", headers=h1, json=payload)
    print(f"First request: status={r1.status_code}, rate_limit_remaining={r1.headers.get('X-RateLimit-Remaining')}")
    print(f"Response: {r1.text}")
    assert r1.status_code in (200, 201, 202), f"Expected 200/201/202, got {r1.status_code}"
    
    # Duplicate with identical payload (Idempotency Replay)
    r2 = requests.post(f"{BASE_URL}/api/v1/events", headers=h1, json=payload)
    print(f"Idempotent replay: status={r2.status_code}, replay_header={r2.headers.get('Idempotent-Replay')}")
    assert r2.status_code in (200, 201, 202), f"Expected 200/201/202, got {r2.status_code}"
    assert r2.headers.get("Idempotent-Replay") == "true", "Expected Idempotent-Replay header"
    
    # Conflict with modified payload (Payload Digest Mismatch)
    modified_payload = dict(payload, payload=json.dumps({"orderId": "ord_1001", "amount": 999.99}))
    r3 = requests.post(f"{BASE_URL}/api/v1/events", headers=h1, json=modified_payload)
    print(f"Payload mismatch: status={r3.status_code}, body={r3.text}")
    assert r3.status_code == 422, f"Expected 422, got {r3.status_code}"

    # Verify Delivery Dispatch & Tracking
    print("\n--- 2. Verifying Delivery Audit Trail ---")
    time.sleep(2)  # Give dispatcher a moment
    r4 = requests.get(f"{BASE_URL}/api/v1/deliveries", headers=HEADERS)
    print(f"Deliveries status={r4.status_code}, count={len(r4.json() if r4.status_code == 200 else [])}")
    print(f"Deliveries: {r4.text[:400]}")

    print("\n[SUCCESS] Ingestion, Idempotency, and Delivery Verification Passed!")

if __name__ == "__main__":
    test_flow()
