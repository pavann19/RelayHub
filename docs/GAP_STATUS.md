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
| GAP-V03 | Runtime/external verification | Local Kind/Calico/TLS/alert receipt passed; hosted CI remains UNVERIFIED without an authorized remote | RH-008 / RH-VAL-008; hosted gate remains |
| GAP-V04 | Capacity/endurance | Final 600-second failure workload and 5/10/20/50 rps ladder passed; 50 rps accumulated backlog | RH-008, RH-009; long-term/production endurance remains unverified |
| GAP-P01 | Production defaults | Fail-closed profile, explicit private bootstrap/revocation, least-privilege OBSERVE role and strict TLS fixtures | RH-007, RH-013 |
| GAP-P02 | Dependency security | Patched HttpCore/Snappy and minimal patched edge; both final images have zero reported High/Critical findings | RH-010, RH-008; scans are time-bound |
| GAP-P03 | Local demo exposure | All eight published Compose ports bind loopback | RH-011 |
| GAP-P04 | Receiver example parity | Python helper rejects duplicate/non-ASCII malformed signatures and extreme replay timestamps | RH-012 |

## Intentional boundaries

- At-least-once delivery remains intentional; stable delivery IDs enable receiver deduplication.
- Kafka quarantine is retained for trusted operator inspection/replay rather than exposed through a cross-tenant API.
- Historical successful audit purging is opt-in; dead letters, quarantine and unresolved outcomes are preserved for operator disposition. Retention defaults do not silently erase existing audit.
- Dedicated custom-CA/hostname/SNI fixtures and local TLS/network enforcement passed. Public certificate lifecycle and actual target-environment controls still require acceptance.
- Public demo keys already in an old database need explicit revocation after private admin provisioning. The upgrade does not silently invalidate credentials.
- JWT/OIDC and OpenTelemetry propagation remain optional unimplemented capabilities; they are not prerequisites of the current API-key-based contract.

See OPERATIONS.md for the migration, retention, quarantine and recovery procedures, and FIX_VALIDATION.md for the historical/current evidence boundary.

## Remaining release gates

1. Configure an authorized Git remote and run hosted CI/security/recovery workflows. No push or public deployment has occurred.
2. Validate supported target Kubernetes/CNI versions, multi-node failure, real ingress/certificate rotation, secret management, runtime database privileges and HPA. The pinned single-node fixture is compatibility evidence.
3. Demonstrate managed database/broker durability, off-site backup restore objectives, representative long-duration load, storage retention and on-call routing. A ten-minute local run is not a production soak or availability SLO.
4. Tune worker/destination fairness and resource budgets if the intended workload exceeds the measured boundary. At 50 rps, backlog grew; sustained 50 rps delivery is not proven.

JWT/OIDC, distributed tracing and additional transports are optional feature work rather than unresolved correctness defects in the implemented contract. Current positioning is a production-oriented, locally verified portfolio release candidate, not production certification.
