# RelayHub: durable webhook delivery under failure

RelayHub accepts tenant events into PostgreSQL and delivers signed webhooks despite temporary receiver failure and application replacement. The engineering case is durable acceptance, recoverable uncertain outcomes and independently tested security boundaries. It is a locally verified release candidate; no managed production operation or hosted availability is claimed.

## Why the design matters

A successful ingestion response commits the event, idempotency response and outbox intent together. If the process dies before publishing, another worker recovers the committed intent. Kafka mode adds a broker-backed transport; direct mode keeps a small deployment. PostgreSQL remains the recovery authority in both modes.

Delivery claims use short transactions and fencing tokens. HTTP happens after database resources are released. Expired claims retain UNKNOWN outcomes, and stale workers cannot finalize another worker's claim. An external receiver acceptance cannot participate in the database transaction, so delivery remains at least once. Receivers deduplicate a stable delivery ID.

The production profile refuses demo keys and unsafe defaults, requires HTTPS destination hosts, disables API documentation, and separates ingestion, administration and observability roles. Application DNS decisions are pinned to connections while hostname verification and SNI remain enabled. Calico egress restrictions provide a separately tested boundary. The NGINX TLS edge and application run with read-only roots, dropped capabilities and no service-account tokens.

## Reproducible evidence

The final application and patched NGINX edge completed a 600-second workload at five requests/second: 3,000 unique events were acknowledged, read back and delivered, with zero observed loss or duplicate receiver acceptances. A controlled 12-second receiver outage produced 229 recorded HTTP 503 attempts; work recovered after the receiver resumed. One application pod was replaced under load. Exact-key replay returned the original event, while a changed payload returned 422. The receiver verified actual payload bytes and HMAC signatures over HTTPS.

The separate final-image drill used SIGKILL after acceptance, recovered the pending outbox in a replacement process, and verified a real PostgreSQL logical backup/restore. Production bootstrap rejected active demo keys, explicitly revoked them while provisioning private ADMIN/INGEST/OBSERVE keys, and refused overwriting the private file without database writes. Prometheus scraped both replicas and Alertmanager delivered firing/resolved quarantine notifications to an isolated local fixture.

The load ladder used two application replicas, one local Kind node, PostgreSQL, direct transport and verified TLS/HMAC delivery. Each phase lasted 60 seconds; every stored payload sequence and receiver count reconciled. The rates below are tested workloads, not a measured maximum or production promise.

| Target requests/s | Unique events | p95 durable acknowledgement | p95 end-to-end delivery | Backlog at stop | Drain after stop |
| --- | --- | --- | --- | --- | --- |
| 5 | 300 | 45.3 ms | 0.847 s | 0 | 0.952 s |
| 10 | 600 | 46.1 ms | 0.860 s | 4 | 1.475 s |
| 20 | 1,200 | 59.5 ms | 0.875 s | 0 | 1.023 s |
| 50 | 3,000 | 103.9 ms | 16.956 s | 824 | 17.254 s |

All 5,100 ladder events completed with no non-201 initial responses or duplicate receiver acceptances observed. At 50 requests/second, intake outpaced delivery and accumulated backlog. Twenty requests/second is the highest tested phase with sub-second p95 delivery; 50 is **not** a demonstrated sustained delivery rate. Queue age/backlog and recovery time matter alongside HTTP latency.

| Evidence | Scope |
| --- | --- |
| RH-VAL-007 | Production profile, certificate/hostname/SNI fixtures, OBSERVE authorization and private bootstrap implementation |
| RH-VAL-008 | Final-stack workload, Calico deny/allow/deny, TLS/HMAC delivery, alert receipt, bootstrap and packaged recovery |
| RH-VAL-009 | Four-phase workload ladder, payload readback, latency, backlog and drain |
| RH-VAL-010 | 84 Java tests, patched transitive libraries, final image identity and zero reported High/Critical scanner findings |
| RH-VAL-011 | All eight local-demo ports bound to loopback |
| RH-VAL-012 | Python receiver helper rejects duplicate/malformed signature fields; seven Python checks total |
| RH-VAL-013 | Private Windows file ACLs applied before credential writes; nine Python checks at the current revision |

The checked-in validation summaries link measurements to artifact hashes and change IDs. Raw local reports, generated keys and certificates remain under ignored target/readiness/. Reproduction commands are in deploy/validation/README.md. Publication and hosted workflow status are tracked in PUBLICATION.md; the measurements in this case study remain local evidence.

The first functional run used a stock edge image that subsequently failed its vulnerability gate. It was replaced before the final repeated acceptance above. Those earlier measurements remain historical and do not certify the patched stack. A later machine/container restart reset the fixture's in-memory counters; the completed reports were preserved, database rows remained, and a fresh post-resume HTTPS/HMAC event passed. That incidental restart is not a planned node-failure or uptime experiment.

## Resume wording supported by this case

- Built a Java/Spring Boot webhook platform with a PostgreSQL transactional outbox and fenced delivery leases; locally validated recovery of 3,000 accepted events through a receiver outage and pod replacement, with zero observed loss.
- Benchmarked a two-replica local pipeline at 20 requests/second with 59.5 ms p95 durable acknowledgement and 0.875 s p95 delivery; reconciled all 5,100 ladder events and identified backlog growth at 50 requests/second.
- Hardened deployment with role-scoped keys, TLS/DNS pinning, enforcing egress policy and private bootstrap; cleared High/Critical dependency gates for both application and edge images and verified firing/resolved operational alerts.

Use one or two bullets appropriate to the role. These are independent project results, not employer/customer impact or managed-production experience.

## Interview boundaries

Two application replicas on one Kind node do not demonstrate node-level availability. PostgreSQL is single-instance in this test; HPA, managed database/broker failover, external certificate rotation, disaster-recovery objectives and long-duration production workloads remain separate target-environment gates. The measured workload uses direct mode; real Kafka behavior is exercised by the Java integration suite.

The implementation and evidence are suitable for discussing backend reliability and platform security. Resume claims must retain the local workload scope and distinguish observed results from guarantees. See INTERVIEW_GUIDE.md for trade-offs and failure semantics.
