# Security

API keys are stored as SHA-256 digests and mapped to a tenant and an allowlisted role (`ROLE_INGEST` or `ROLE_ADMIN`). Event and delivery APIs require either role. Subscription management, key management and replay require ADMIN. Actuator metrics require ADMIN; health and API documentation are public. JWT/OIDC authentication is not configured.

Tenant checks apply to event reads, subscription operations, replay and delivery reads, including listing deliveries by event ID. Ingestion credentials cannot mint keys or choose higher privileges. New keys' roles are validated.

No demo credentials are seeded by default. Local Compose explicitly opts into `data.sql`; its published demo keys are unsuitable for public use. Production operators must provision their own tenant and hashed initial admin key through a trusted administrative path. The project does not implement a public bootstrap endpoint.

Webhook targets require an exact hostname in `RELAYHUB_WEBHOOK_ALLOWED_HOSTS`. URLs with credentials/fragments or unsupported schemes are rejected. Private/reserved addresses require a separate explicit opt-in. Every attempt revalidates the destination; redirects are never followed. The receiver body is limited to a 1024-byte preview and the request/body completion has a timeout. Custom signing secrets must have 16–255 characters.

The Java HTTP client's DNS lookup is separate from validation. Use outbound firewall/proxy policy to prevent DNS rebinding and access to cloud metadata/internal services; application checks alone cannot provide that guarantee. Only allow operator-trusted hostnames. The private-host override is intended for a controlled demo or explicitly trusted network.

Outbound requests carry `X-RelayHub-Signature: t=<epoch>,v1=<HMAC-SHA256(timestamp.payload)>` and a stable `X-RelayHub-Delivery` ID. Receivers must verify the exact raw payload bytes, timestamp tolerance and signature, and deduplicate delivery IDs. The standalone verifier is in `scripts/verify-signature.py`.

TLS termination, external secret management, egress restrictions, operational key rotation, payload retention/encryption policy and a deployment security review remain operator responsibilities. No production security certification is claimed.

### RH-004 behavior update

Subscription deletion disables future delivery and preserves existing audit/history. Cancelled work does not create a fabricated HTTP attempt. Outbox failures use 30-second deferral and quarantine after five failures; Kafka poison records receive two retries then durable database quarantine. Policy-approved DNS addresses are pinned to each outbound connection while hostname verification remains enabled. JSON API bodies are limited to 1 MiB; oversized requests return 413. These rules supersede earlier descriptions of physical subscription deletion, unlimited poison-record retries, or separate unchecked HTTP DNS resolution. See FIX_VALIDATION.md (under docs/) for evidence boundaries.
