-- Seed Initial Tenants (ANSI SQL compatible for PostgreSQL and H2)
INSERT INTO tenants (id, name, tier, rate_limit_per_second, burst_capacity, monthly_quota)
SELECT 'tenant_default', 'Acme Corp Default Tenant', 'PRO', 50, 100, 500000
WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE id = 'tenant_default');

INSERT INTO tenants (id, name, tier, rate_limit_per_second, burst_capacity, monthly_quota)
SELECT 'tenant_free', 'Starter Free Tier Tenant', 'FREE', 5, 10, 10000
WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE id = 'tenant_free');

-- Seed Initial API Keys
-- Demo Ingest Key: rlh_live_demo1234567890abcdef
INSERT INTO api_keys (id, tenant_id, key_prefix, key_hash, name, role, is_active)
SELECT 'key_demo_ingest', 'tenant_default', 'rlh_live_demo1234', 'cc907404f3d66fadd66fc43b1f2e7fa42c66ec671b598bd4efec622863aea79e', 'Default Ingestion Key', 'ROLE_INGEST', TRUE
WHERE NOT EXISTS (SELECT 1 FROM api_keys WHERE id = 'key_demo_ingest');

-- Demo Admin Key: rlh_admin_9876543210fedcba
INSERT INTO api_keys (id, tenant_id, key_prefix, key_hash, name, role, is_active)
SELECT 'key_demo_admin', 'tenant_default', 'rlh_admin_987654', 'e5da79c469b6f6f249bfc149bd37184da6231a68f8e96fb01f9cb4587709798a', 'Default Admin Key', 'ROLE_ADMIN', TRUE
WHERE NOT EXISTS (SELECT 1 FROM api_keys WHERE id = 'key_demo_admin');
