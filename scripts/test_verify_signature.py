import hashlib
import hmac
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('signatures',Path(__file__).with_name('verify-signature.py'))
signatures=importlib.util.module_from_spec(spec);spec.loader.exec_module(signatures)


class SignatureTests(unittest.TestCase):
    secret='isolated-fixture-secret'
    now=1800000000
    payload=b'{"message":"hello"}'

    def header(self,timestamp=None,payload=None):
        timestamp=self.now if timestamp is None else timestamp
        payload=self.payload if payload is None else payload
        digest=hmac.new(self.secret.encode(),str(timestamp).encode()+b'.'+payload,hashlib.sha256).hexdigest()
        return f't={timestamp},v1={digest}'

    def verify(self,header,payload=None,**kwargs):
        with patch.object(signatures.time,'time',return_value=self.now):
            return signatures.verify_relayhub_signature(header,self.payload if payload is None else payload,self.secret,**kwargs)

    def test_valid_raw_bytes_and_text_and_empty_payload(self):
        self.assertTrue(self.verify(self.header()))
        self.assertTrue(self.verify(self.header(),self.payload.decode()))
        self.assertTrue(self.verify(self.header(payload=b''),b''))

    def test_tamper_and_wrong_signature_rejected(self):
        self.assertFalse(self.verify(self.header(),b'changed'))
        self.assertFalse(self.verify(f't={self.now},v1='+('0'*64)))

    def test_duplicate_signed_fields_rejected(self):
        self.assertFalse(self.verify(self.header()+f',t={self.now}'))
        self.assertFalse(self.verify(self.header()+',v1='+('0'*64)))

    def test_replay_future_extreme_timestamp_and_negative_tolerance(self):
        for timestamp in (self.now-301,self.now+301,-2**63,2**63,10**30):
            self.assertFalse(self.verify(self.header(timestamp)))
        self.assertFalse(self.verify(self.header(),tolerance_seconds=-1))

    def test_malformed_and_non_ascii_signature_fail_without_throwing(self):
        for header in ('t=1',f't={self.now},v1=é',f't=no,v1='+('0'*64)):
            self.assertFalse(self.verify(header))


if __name__=='__main__':
    unittest.main()
