# Security

API keys are stored as SHA-256 digests and mapped to a tenant and an allowlisted role (INGEST, ADMIN or OBSERVE). Event and delivery APIs require INGEST/ADMIN. Subscription management, key management and replay require ADMIN. Prometheus/metrics allow OBSERVE or ADMIN; OBSERVE cannot access event or key-management APIs. Health is public; API documentation is disabled by the production profile. JWT/OIDC authentication is not configured.

Tenant checks apply to event reads, subscription operations, replay and delivery reads, including listing deliveries by event ID. Ingestion credentials cannot mint keys or choose higher privileges. New keys' roles are validated.

No demo credentials are seeded by default. Local Compose explicitly opts into `data.sql`; its published demo keys are unsuitable for public use. Production operators must provision their own tenant and hashed initial admin key through a trusted administrative path. The project does not implement a public bootstrap endpoint.

Webhook targets require an exact hostname in `RELAYHUB_WEBHOOK_ALLOWED_HOSTS`. URLs with credentials/fragments or unsupported schemes are rejected. Private/reserved addresses require a separate explicit opt-in. Every attempt revalidates the destination; redirects are never followed. The receiver body is limited to a 1024-byte preview and the request/body completion has a timeout. Custom signing secrets must have 16–255 characters.

The HTTP client connects only to the exact IP addresses approved by policy for that attempt, retaining the original hostname for certificate validation and SNI. Redirects are disabled. Enforcing CNI policy independently restricts egress. Only allow operator-trusted hostnames. The private-host override is intended for a controlled demo or explicitly trusted network and requires an explicit matching network-policy exception.

Outbound requests carry `X-RelayHub-Signature: t=<epoch>,v1=<HMAC-SHA256(timestamp.payload)>` and a stable `X-RelayHub-Delivery` ID. Receivers must verify the exact raw payload bytes, timestamp tolerance and signature, and deduplicate delivery IDs. The standalone verifier is in `scripts/verify-signature.py`.

TLS termination, external secret management, egress restrictions, operational key rotation, payload retention/encryption policy and a deployment security review remain operator responsibilities. No production security certification is claimed.

The production profile refuses published demo API keys, wildcard/empty host allowlists, non-HTTPS targets and unsafe database credentials. The trusted bootstrap tool writes generated raw keys to a new private file and persists hashes transactionally. Local Compose binds all published ports to loopback and intentionally uses demo credentials; it is not a production deployment. See PRODUCTION_PROFILE.md, DEPENDENCY_SECURITY.md and the validation ledger for current artifact-scoped evidence.

### RH-004 behavior update

Subscription deletion disables future delivery and preserves existing audit/history. Cancelled work does not create a fabricated HTTP attempt. Outbox failures use 30-second deferral and quarantine after five failures; Kafka poison records receive two retries then durable database quarantine. Policy-approved DNS addresses are pinned to each outbound connection while hostname verification remains enabled. JSON API bodies are limited to 1 MiB; oversized requests return 413. These rules supersede earlier descriptions of physical subscription deletion, unlimited poison-record retries, or separate unchecked HTTP DNS resolution. See FIX_VALIDATION.md (under docs/) for evidence boundaries.
