# RelayHub — Security Boundaries & Cryptographic Contracts

RelayHub enforces strict security boundaries across API ingress authentication, multi-tenant isolation, outbound webhook request signing, and replay attack prevention.

---

## 1. Authentication Mechanisms

RelayHub supports dual authentication schemes tailored for programmatic services and enterprise identity providers:

### A. Scoped API Keys (`X-API-Key`)
- **Key Format**: `rlh_{environment}_{random_hex_24_bytes}` (e.g. `rlh_live_9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d`).
- **One-Way Cryptographic Hashing**: Raw API keys are displayed **only once** upon generation. The database stores only the SHA-256 digest (`key_hash`). Database compromises reveal zero valid raw credentials.
- **Prefix Matching**: Keys carry a 16-character prefix (`key_prefix`, e.g. `rlh_live_9a8b7c6d`) to facilitate fast indexing and auditing in operational dashboards without exposing credentials.
- **Revocation & Expiry**: Keys support explicit deactivation (`is_active = FALSE`) and optional expiration timestamps (`expires_at`).

### B. OAuth2 / OIDC JWT Bearer Tokens (`Authorization: Bearer <token>`)
- RelayHub acts as an RFC 6750 / RFC 7519 Resource Server.
- Validates token signature, expiration (`exp`), and issuer (`iss`).
- Translates `roles` / `scope` claims into Spring Security authorities (`ROLE_INGEST`, `ROLE_ADMIN`, `ROLE_SUBSCRIBER`).

---

## 2. Webhook Request Signing (`HMAC-SHA256`)

Every outbound HTTP request delivered to a downstream webhook subscription is cryptographically signed using the subscriber's shared secret (`sec_live_...`).

### The Signature Header
RelayHub attaches the header:
```http
X-RelayHub-Signature: t=1700000000,v1=9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

### Signature Computation Contract
```
signed_payload = "${timestamp}.${raw_json_body}"
signature = HmacSHA256(signed_payload, subscription_secret).toHex()
```

### Downstream Verification Protocol
External services consuming RelayHub webhooks must follow this protocol:
1. Split `X-RelayHub-Signature` by comma `,` and parse key-value pairs (`t` and `v1`).
2. **Replay Attack Check**: Ensure `|current_timestamp - t| <= 300` seconds (5 minutes). Replay requests beyond this window must be rejected immediately.
3. Compute expected HMAC: `HmacSHA256("${t}.${raw_request_body}", secret)`.
4. **Timing-Safe Comparison**: Compare `v1` and expected HMAC using constant-time equality check (e.g., `MessageDigest.isEqual` in Java, `hmac.compare_digest` in Python) to prevent timing side-channel attacks.

---

## 3. Threat Mitigation Matrix

| Threat | RelayHub Mitigation |
| :--- | :--- |
| **Credential Theft via DB Dump** | All API keys stored as SHA-256 digests; raw keys never logged or stored. |
| **Man-in-the-Middle (MitM)** | TLS 1.3 enforced for public endpoints; HTTPS target URLs required for webhooks. |
| **Replay Attacks on Webhooks** | `t={timestamp}` envelope inside HMAC signature with 300s expiration tolerance. |
| **Timing Side-Channel Attacks** | Constant-time `MessageDigest.isEqual` / `hmac.compare_digest` comparisons. |
| **Cross-Tenant Data Leakage** | All database queries strictly parameterized by authenticated `tenant_id`. |
| **Downstream Denial of Service** | Configurable per-tenant rate limits, burst smoothing, and connect/read timeouts. |
| **Memory Exhaustion via Bad Payloads** | Inbound bodies capped; outbound response previews truncated to 1024 bytes. |
