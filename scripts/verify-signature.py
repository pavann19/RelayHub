#!/usr/bin/env python3
"""
RelayHub Webhook Signature Verification Helper
Demonstrates how downstream consumer applications verify X-RelayHub-Signature headers.
"""

import hmac
import hashlib
import time
import sys
import re

def verify_relayhub_signature(signature_header: str, raw_payload: str | bytes, shared_secret: str, tolerance_seconds: int = 300) -> bool:
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
    if not signature_header or raw_payload is None or not shared_secret or tolerance_seconds < 0:
        return False
        
    parts = {}
    for part in signature_header.split(','):
        if '=' not in part:
            continue
        name, value = (value.strip() for value in part.split('=', 1))
        if name in ('t', 'v1') and name in parts:
            return False
        parts[name] = value
    timestamp_str = parts.get('t')
    signature = parts.get('v1')
    
    if not timestamp_str or not signature or not re.fullmatch(r'[0-9a-f]{64}', signature):
        return False
    if not re.fullmatch(r'[+-]?[0-9]{1,19}', timestamp_str):
        return False
        
    try:
        timestamp = int(timestamp_str)
    except ValueError:
        return False
    if not -(2**63) <= timestamp < 2**63:
        return False
        
    current_time = int(time.time())
    if abs(current_time - timestamp) > tolerance_seconds:
        return False
        
    payload_bytes = raw_payload if isinstance(raw_payload, bytes) else raw_payload.encode('utf-8')
    signed_payload = str(timestamp).encode('ascii') + b'.' + payload_bytes
    expected_hmac = hmac.new(
        shared_secret.encode('utf-8'),
        signed_payload,
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

