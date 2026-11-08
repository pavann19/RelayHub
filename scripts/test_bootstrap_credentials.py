import importlib.util
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("bootstrap", Path(__file__).with_name("bootstrap-credentials.py"))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class BootstrapTests(unittest.TestCase):
    def test_sql_never_contains_raw_keys_and_is_transactional(self):
        sql, keys = bootstrap.sql_plan("tenant_demo", "Tenant's name", True)
        self.assertTrue(sql.startswith("BEGIN;"))
        self.assertTrue(sql.endswith("COMMIT;"))
        self.assertIn("Tenant''s name", sql)
        self.assertIn("ROLE_OBSERVE", sql)
        for key in keys.values():
            self.assertNotIn(key, sql)
        self.assertEqual(3, len(set(keys.values())))

    def test_rejects_tenant_identifier_injection(self):
        for identifier in ("tenant';DROP TABLE tenants;--", "x" * 65, ""):
            with self.assertRaises(ValueError):
                bootstrap.sql_plan(identifier, "Name")

    def test_windows_acl_applies_to_empty_file_before_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "keys.private.json"
            def restrict(*args, **kwargs):
                self.assertEqual(b"", path.read_bytes())
            with patch.object(bootstrap.os, "name", "nt"), patch.object(bootstrap.subprocess, "check_output", return_value="test-user\n"), patch.object(bootstrap.subprocess, "run", side_effect=restrict) as acl:
                bootstrap.write_private_json(path, {"key": "private-value"})
                acl.assert_called_once()
            self.assertIn("private-value", path.read_text())

    def test_acl_failure_leaves_no_credentials_in_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "keys.private.json"
            with patch.object(bootstrap.os, "name", "nt"), patch.object(bootstrap.subprocess, "check_output", return_value="test-user\n"), patch.object(bootstrap.subprocess, "run", side_effect=RuntimeError("ACL failed")):
                with self.assertRaises(RuntimeError):
                    bootstrap.write_private_json(path, {"key": "private-value"})
            self.assertEqual(b"", path.read_bytes())


if __name__ == "__main__":
    unittest.main()
