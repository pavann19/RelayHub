import importlib.util
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()
