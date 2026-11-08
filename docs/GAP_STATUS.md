# Gap closure status

Status is scoped to local code and local validation. IDs below map the 2026-11-08 gap review to implementation/evidence. No production or hosted result is inferred.

| Gap ID | Priority | Result | Change / validation |
| --- | --- | --- | --- |
| GAP-H01 | High | Subscription DELETE disables delivery and preserves audit; future work cancels | RH-004 / RH-VAL-004 |
| GAP-H02 | High | Outbox errors defer/quarantine; Kafka poison records receive bounded retries then durable quarantine | RH-004 / RH-VAL-004 |
| GAP-H03 | High | Approved DNS addresses are pinned per connection; Host/TLS hostname retained; redirects disabled | RH-004 / RH-VAL-004 |
| GAP-M01 | Medium | 1 MiB request cap, database-aligned field lengths, control/null rejection | RH-004 / RH-VAL-004 |
| GAP-M02 | Medium | Bounded expired-idempotency cleanup and explicit opt-in successful-event/audit retention | RH-005 / RH-VAL-005 |
| GAP-M03 | Medium | Runtime HTTP occurs outside DB transactions; fenced leases preserve/recover uncertain claims | RH-005 / RH-VAL-005 |
| GAP-M04 | Medium | Empty event replay reports zero; operations retry reports actual queued count | RH-004, RH-005 |
| GAP-M05 | Medium | Cached queue/age/quarantine gauges, refresh/worker errors, bounded metric labels | RH-005 / RH-VAL-005 |
| GAP-V01 | Upgrade | Versioned checksummed migrations, tested legacy upgrade, admin preview/reconciliation | RH-005 / RH-VAL-005 |
| GAP-V02 | Local artifact verification | Full suite, current source image, process replacement, logical backup restore, bounded correctness run | RH-VAL-005 final summary |
| GAP-V03 | External verification | Hosted CI and Kubernetes/CNI runtime remain UNVERIFIED; CI recovery step and egress manifest prepared | Requires approved target environment/publication |
| GAP-V04 | Capacity/endurance | A 60-second local correctness run is not a sustained-capacity or production-availability demonstration | Long-duration and capacity testing remain UNVERIFIED |

## Intentional boundaries

- At-least-once delivery remains intentional; stable delivery IDs enable receiver deduplication.
- Kafka quarantine is retained for trusted operator inspection/replay rather than exposed through a cross-tenant API.
- Historical successful audit purging is opt-in; dead letters, quarantine and unresolved outcomes are preserved for operator disposition. Retention defaults do not silently erase existing audit.
- TLS verification uses the HTTP library's secure defaults. A dedicated custom-CA/SNI fixture was not added; live TLS infrastructure and network enforcement need target-environment tests.
- Public demo keys already in an old database need explicit revocation after private admin provisioning. The upgrade does not silently invalidate credentials.
- JWT/OIDC and OpenTelemetry propagation remain optional unimplemented capabilities; they are not prerequisites of the current API-key-based contract.

See OPERATIONS.md for the migration, retention, quarantine and recovery procedures, and FIX_VALIDATION.md for the historical/current evidence boundary.
