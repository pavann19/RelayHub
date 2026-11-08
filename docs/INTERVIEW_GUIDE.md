# RelayHub interview walkthrough

## Problem and contract

A client needs a durable acknowledgement even when its webhook receiver is down. An HTTP success from ingestion means event data and publication intent committed together. It does not mean the receiver already accepted the webhook. Delivery is at least once, and receivers deduplicate a stable delivery ID. There is no exactly-once external HTTP guarantee.

## Five-minute source and evidence walkthrough

1. Open the portfolio case study and its checked-in JSON evidence. Identify the source commit, final image/JAR hashes, workload and local scope before discussing results.
2. Follow EventIngressController: tenant authentication, shared quotas and one transaction containing event, idempotency response and outbox. Explain why a database write followed by an unprotected Kafka send would lose work.
3. Follow DurableWorkScheduler and DeliveryLeaseWorker: short claim transaction, HTTP after releasing database resources, token-checked finalization, expiry and UNKNOWN audit. Explain why the receiver's accepted side effect cannot roll back with the database.
4. Show the failure drill: controlled receiver 503s, application pod replacement, final database/receiver reconciliation and exact-key retry of uncertain client outcomes. Explain why zero observed duplicates in this run does not change the at-least-once contract.
5. Show the security boundary: production startup rejects known demo keys; OBSERVE cannot access event APIs; certificate/hostname/SNI fixtures remain strict; approved DNS is pinned and Calico independently denies private destinations. Show firing and resolved alert receipts.

## Decisions worth defending

| Decision | Benefit | Cost or boundary |
| --- | --- | --- |
| PostgreSQL transactional outbox | Event acceptance and recoverable work share a commit | Database storage, polling and cleanup need operating limits |
| Shared database quota state | Consistent limits across application replicas and restarts | A busy tenant serializes quota-row updates |
| Fenced expiring delivery claims | HTTP does not hold a database connection; stale workers cannot finalize | Expiry can repeat an external request; receiver deduplication remains required |
| Durable quarantine after bounded retries | Poison input cannot silently disappear or block a partition forever | Trusted operators must inspect/release retained work |
| Exact host allowlist, pinned DNS and CNI | Independent application and network controls for outbound requests | Approved private endpoints and DNS topology require explicit policy changes |
| API keys with three roles | A compact implemented authorization contract | No JWT/OIDC lifecycle or tenant identity federation |
| Optional Kafka, database recovery authority | Small direct-mode deployment and broker-mode integration | Kafka adds operational cost without making external HTTP exactly once |
| Opt-in successful-event retention | Avoid silent audit deletion on upgrade | Operators must choose retention and monitor disk growth |

## Questions to answer candidly

- **Is this production-operated?** No. It is a locally verified release candidate with production-oriented controls. Hosted CI, managed infrastructure, multi-node failure, long-term endurance and production SLOs require separate evidence.
- **Does a clean scanner result prove security?** No. It is a time-bound dependency scan for stated severities. Behavioral authorization, TLS, SSRF and request-validation checks are separate evidence.
- **What happens after receiver acceptance and sender death?** The claim may expire with an uncertain outcome and be retried. The receiver must deduplicate X-RelayHub-Delivery. Never describe this as exactly once.
- **Can it handle a hot tenant or many slow destinations?** The measured local ladder is the available evidence. Scheduler batching, database quota contention and destination fairness still bound capacity. Do not extrapolate to an untested workload or hosted topology.
- **What would you do before a real release?** Run hosted CI and the target-environment acceptance matrix; pin reviewed images; provision managed secrets, database/broker durability, TLS and backups; demonstrate restore objectives, multi-node failure and a representative long soak; then set measured SLOs and on-call thresholds.

Use the case study's measured resume bullets and preserve their local scope. Do not substitute theoretical thread counts or historical load scripts for measured throughput.
