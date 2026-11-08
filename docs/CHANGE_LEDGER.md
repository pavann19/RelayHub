# Local change and validation ledger

Every subsequent change receives a stable `RH-NNN` Change-ID and a corresponding `RH-VAL-NNN` validation ID. Record full commit hashes after the implementation commit exists, and preserve measured, historical and unverified scopes separately. Implementation commits include these IDs as trailers. Do not amend older evidence to imply it validates newer code. Changes stay local unless publication is explicitly authorized.

| Change ID | Local implementation commit | Scope | Validation ID | Result |
| --- | --- | --- | --- | --- |
| RH-001 | `9d1528be373cefa8bd2e206e6e5a9b610d1d2c42` | Durable delivery, authorization, quotas, tenant isolation, destination restrictions | RH-VAL-001 | 54 tests passed; packaged restart recovery passed |
| RH-003 | `28c43d2` | Local change/validation ledger | RH-VAL-003 | Commit references, XML/JSON evidence and whitespace checked |
| RH-004 | `0cc7410c978b827f66b539668ec8f49749317102` | Audit preservation, poison-work isolation, pinned DNS, input limits and replay accuracy | RH-VAL-004 | 65 tests passed |
| RH-002 | `7836907672be5640d0cf9be781671a2bdbe61d7e` | Signature timestamp overflow and duplicate-field rejection | RH-VAL-002 | 9 focused tests passed; two regressions reproduced before fix |

RH-001 and RH-VAL-001 are retrospective tracking IDs; its existing commit message is preserved. Evidence: [RH-VAL-001](validation/fix-validation-2026-11-07.json), [RH-VAL-002](validation/RH-VAL-002.json), and [scope/limitations](FIX_VALIDATION.md).

## Artifact identities

- RH-VAL-001 JAR SHA-256: `7114d44919997ff9596579971e315aa5f139245adad5a6f1fd4671e2762a9121`.
- RH-VAL-001 local image: `relayhub:verified-fixes`; image ID: `sha256:8bf0015a3e1bc7c12bd93b9e62dc3abad4748f6aff9fd88a689987ac58a6b2f2`.
- These artifacts were verified before RH-002 and do not contain the signature follow-up. RH-002 did not rerun the full suite or rebuild the image.

## Tracking record

RH-003 establishes this ledger and the RH-VAL-002 checked-in summary. RH-VAL-003 validates its referenced implementation commits with `git cat-file`, the signature suite XML, JSON parsing and `git diff --check`. Its own commit is resolved from Git by `git log --format="%H %s" --grep="Change-ID: RH-003"`; a file cannot embed the hash of the commit containing itself. Future implementation hashes are recorded in the next tracking update using the same approach.

RH-005 adds delivery leases, queue/worker observability, opt-in retention, Flyway migration/adoption, legacy-event reconciliation, a reference egress policy, and reproducible packaged restart/backup checks. Its commit and final RH-VAL-005 artifact identities are recorded by the following RH-006 tracking update so that the implementation commit hash is known before recording it.
