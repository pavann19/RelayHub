# Production profile and credential provisioning

Run with `SPRING_PROFILES_ACTIVE=production`. Provide database URL, username, a unique password of at least 16 characters, and explicit `RELAYHUB_WEBHOOK_ALLOWED_HOSTS`. Keep private targets disabled except an explicitly approved private integration. HTTPS is required for webhook subscriptions. API documentation and demo seeding are disabled. Readiness includes PostgreSQL; liveness does not depend on PostgreSQL. The application refuses published demo API keys and unsafe database/host defaults before serving traffic.

This profile is deployment hardening, not proof of managed production operation. Kubernetes reference manifests still need a managed database/broker, TLS ingress, secret manager, backup schedule, capacity planning, monitoring and target-environment acceptance.

## Fresh database

1. Back up any existing database before migration. Use an isolated copy to rehearse upgrades. Never point a validation script at a user database.
2. Run the image with the production environment and `--migrate-only`. It migrates and validates Flyway, disables delivery workers, and exits. A failed guard/migration is a failed job; do not enable traffic.
3. Preview tenant provisioning: `python scripts/bootstrap-credentials.py --container <postgres-container> --database <database> --db-user <user> --tenant <id>`.
4. Apply with `--apply --output <private-path>.private.json`. This generates ADMIN, INGEST and OBSERVE keys. Raw keys go only to the new private file; SQL stores SHA-256 hashes. The script refuses overwriting the file. Keep it outside source control and transfer keys through the intended secret manager.
5. Create `relayhub-secrets` externally with `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`; no credential Secret is committed. Configure a restricted OBSERVE key in Prometheus through a mounted credential file.
6. Start application replicas; check readiness, unknown-key rejection and HTTPS receiver delivery before allowing clients.

## Existing demo database

Use the existing migration/backup procedure in OPERATIONS.md. Published demo credentials are never silently retained by the production profile: it refuses startup while they are active. Provision private administrator keys and explicitly revoke the published demo keys for each affected tenant (`--revoke-demo-keys` with the bootstrap script). Validate key counts and authentication on the restored copy before the real maintenance window. Old unversioned schemas require explicit `RELAYHUB_BASELINE_EXISTING=true` only after reviewing the schema and backup.

If provisioning reports an uncertain commit, retain the private credentials file and inspect the database by key prefix/hash before retrying. Blind retries could create additional valid keys.

The migration job uses application database credentials. For a hosted deployment, split migration DDL privileges from runtime DML privileges and verify grants against the selected PostgreSQL service. This repository does not claim a tested managed-service privilege split.
