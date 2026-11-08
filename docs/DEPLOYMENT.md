# Deployment

## Reproducible local demo

Use JDK 21 and Docker Desktop. Run `./mvnw test`, then `docker compose -f deploy/docker-compose.yml up --build -d`. Compose builds the application image from the repository and starts two application instances against shared PostgreSQL/Kafka. Published application ports are 8080 and 8081. `scripts/local-demo.ps1` uses explicit local demo keys and the container-network receiver address.

Production application defaults do not seed tenants or keys. Compose opts into `RELAYHUB_SEED_LOCATION=classpath:data.sql` and allows only `mock-receiver`, with the private-address override enabled. Provision production tenant/admin records through a trusted administrative path; do not enable demo seeds there. Database schema initialization fails on SQL errors instead of continuing silently.

Important environment variables:

| Variable | Purpose |
| --- | --- |
| SPRING_DATASOURCE_URL / USERNAME / PASSWORD | PostgreSQL connection |
| KAFKA_ENABLED / KAFKA_BOOTSTRAP_SERVERS | Optional Kafka transport |
| RELAYHUB_WEBHOOK_ALLOWED_HOSTS | Exact destination hostname allowlist; empty denies all |
| RELAYHUB_WEBHOOK_ALLOW_PRIVATE_TARGETS | Explicit private-network override; default false |
| RELAYHUB_WORKERS_ENABLED | Durable workers; default true |
| RELAYHUB_SEED_LOCATION | Empty SQL by default; local demo may select data.sql |

Metrics require an admin key. The bundled Prometheus configuration uses the public local-demo admin credential; replace it with a protected credential for any real deployment. Health probes remain public.

## Upgrade and recovery

The additive schema preserves existing events/deliveries and creates outbox, attempt-history and shared-limiter tables. Historical delivery attempts cannot be reconstructed from overwritten rows. Existing pre-upgrade PENDING events may have no publication intent; inspect/reconcile them explicitly before claiming complete recovery. Existing idempotency records use the old hash format until their recorded expiry; do not silently delete them during an upgrade. Previously seeded demo keys remain in an existing database and must be explicitly revoked before exposing it.

A restart resumes committed outbox and due-delivery rows. Locks are database transactions, with no process-local ownership lease to repair. Remote acceptance before a sender crash can still produce duplicate requests; consumers must deduplicate stable delivery IDs.

Kubernetes files are reference manifests and have not been runtime-validated by this fix. Configure real secrets, provisioned tenants/keys, allowed hosts, image registry, ingress TLS, outbound network restrictions, backup/retention policy and resource limits before deployment. No public deployment is part of this change.

### RH-004 behavior update

Subscription deletion disables future delivery and preserves existing audit/history. Cancelled work does not create a fabricated HTTP attempt. Outbox failures use 30-second deferral and quarantine after five failures; Kafka poison records receive two retries then durable database quarantine. Policy-approved DNS addresses are pinned to each outbound connection while hostname verification remains enabled. JSON API bodies are limited to 1 MiB; oversized requests return 413. These rules supersede earlier descriptions of physical subscription deletion, unlimited poison-record retries, or separate unchecked HTTP DNS resolution. See FIX_VALIDATION.md (under docs/) for evidence boundaries.
