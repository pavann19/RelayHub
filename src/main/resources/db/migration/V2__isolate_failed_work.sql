-- RH-004 additive work-isolation upgrade (safe to run on an existing database).
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS failure_count INT NOT NULL DEFAULT 0;
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS quarantined_at TIMESTAMPTZ;
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS last_error TEXT;
CREATE TABLE IF NOT EXISTS kafka_quarantine (
    topic TEXT NOT NULL, partition_id INT NOT NULL, record_offset BIGINT NOT NULL,
    payload TEXT, error_type TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(topic, partition_id, record_offset)
);

