#!/usr/bin/env python3
"""
RelayHub Webhook Signature Verification Helper
Demonstrates how downstream consumer applications verify X-RelayHub-Signature headers.
"""

import hmac
import hashlib
import time
import sys

def verify_relayhub_signature(signature_header: str, raw_payload: str, shared_secret: str, tolerance_seconds: int = 300) -> bool:
    """
    Verifies that the incoming webhook payload was authentically signed by RelayHub.
    
    Args:
        signature_header: The value of header X-RelayHub-Signature (format: t=1700000000,v1=hex_hmac)
        raw_payload: The raw HTTP request body string
        shared_secret: The endpoint's configured secret (e.g. sec_live_...)
        tolerance_seconds: Maximum allowed clock drift in seconds (default: 300 / 5 mins)
        
    Returns:
        True if signature is valid and within tolerance window, False otherwise.
    """
    if not signature_header or not raw_payload or not shared_secret:
        return False
        
    parts = dict(part.strip().split('=', 1) for part in signature_header.split(',') if '=' in part)
    timestamp_str = parts.get('t')
    signature = parts.get('v1')
    
    if not timestamp_str or not signature:
        return False
        
    try:
        timestamp = int(timestamp_str)
    except ValueError:
        return False
        
    current_time = int(time.time())
    if abs(current_time - timestamp) > tolerance_seconds:
        print(f"[REJECTED] Timestamp outside tolerance window: delta={abs(current_time - timestamp)}s > {tolerance_seconds}s")
        return False
        
    signed_payload = f"{timestamp}.{raw_payload}"
    expected_hmac = hmac.new(
        shared_secret.encode('utf-8'),
        signed_payload.encode('utf-8'),
        hashlib.sha256
    ).hexdigest()
    
    # Constant-time comparison to protect against timing attacks
    return hmac.compare_digest(signature, expected_hmac)


if __name__ == "__main__":
    secret = "sec_live_9f86d081884c7d659a2feaa0c55ad015"
    payload = '{"orderId":"ord_999","amount":150.00,"currency":"EUR"}'
    ts = int(time.time())
    
    # Generate signature
    signed_data = f"{ts}.{payload}"
    sig = hmac.new(secret.encode(), signed_data.encode(), hashlib.sha256).hexdigest()
    header = f"t={ts},v1={sig}"
    
    print(f"Testing RelayHub Signature Verification:")
    print(f"Header:  {header}")
    print(f"Payload: {payload}")
    
    valid = verify_relayhub_signature(header, payload, secret)
    print(f"Valid Signature: {valid}")
    assert valid, "Valid signature verification failed!"
    
    tampered_payload = '{"orderId":"ord_999","amount":99999.00,"currency":"EUR"}'
    invalid = verify_relayhub_signature(header, tampered_payload, secret)
    print(f"Tampered Payload Valid: {invalid}")
    assert not invalid, "Tampered payload verification should have failed!"
    
    print("\n✓ Verification logic passed all assertions successfully.")

