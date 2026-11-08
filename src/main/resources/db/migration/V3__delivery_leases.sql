ALTER TABLE webhook_deliveries ADD COLUMN IF NOT EXISTS claim_token VARCHAR(64);
CREATE TABLE IF NOT EXISTS webhook_delivery_claims (
    token VARCHAR(64) PRIMARY KEY, delivery_id VARCHAR(64) NOT NULL REFERENCES webhook_deliveries(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL, started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMPTZ NOT NULL, finished_at TIMESTAMPTZ, outcome VARCHAR(32) NOT NULL DEFAULT 'IN_FLIGHT'
);
CREATE INDEX IF NOT EXISTS idx_delivery_inflight ON webhook_deliveries(next_retry_at) WHERE status = 'IN_FLIGHT';
