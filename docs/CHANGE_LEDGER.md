# Local change and validation ledger

Every subsequent change receives a stable `RH-NNN` Change-ID and a corresponding `RH-VAL-NNN` validation ID. Record full commit hashes after the implementation commit exists, and preserve measured, historical and unverified scopes separately. Implementation commits include these IDs as trailers. Do not amend older evidence to imply it validates newer code. Changes stay local unless publication is explicitly authorized.

| Change ID | Local implementation commit | Scope | Validation ID | Result |
| --- | --- | --- | --- | --- |
| RH-001 | `9d1528be373cefa8bd2e206e6e5a9b610d1d2c42` | Initial durable delivery/authorization fixes | RH-VAL-001 | 54 tests and packaged restart recovery passed |
| RH-002 | `7836907672be5640d0cf9be781671a2bdbe61d7e` | Signature verifier hardening | RH-VAL-002 | 9 focused tests passed |
| RH-003 | `28c43d2` | Tracking ledger | RH-VAL-003 | Commit references/XML/JSON verified |
| RH-004 | `0cc7410c978b827f66b539668ec8f49749317102` | Audit, quarantine, DNS pinning, input limits, replay accuracy | RH-VAL-004 | 65 tests passed |
| RH-005 | `cfa7fddea94ae987ed3bf97911b32c35ce74a7f5` | Fenced leases, metrics, retention, migrations, operational recovery | RH-VAL-005 | 75 tests; source image; restart/backup restore; bounded correctness run passed |


RH-001 and RH-VAL-001 are retrospective tracking IDs; its existing commit message is preserved. Evidence: [RH-VAL-001](validation/fix-validation-2026-11-07.json), [RH-VAL-002](validation/RH-VAL-002.json), and [scope/limitations](FIX_VALIDATION.md).

## Artifact identities

- RH-VAL-001 JAR SHA-256: `7114d44919997ff9596579971e315aa5f139245adad5a6f1fd4671e2762a9121`.
- RH-VAL-001 local image: `relayhub:verified-fixes`; image ID: `sha256:8bf0015a3e1bc7c12bd93b9e62dc3abad4748f6aff9fd88a689987ac58a6b2f2`.
- These artifacts were verified before RH-002 and do not contain the signature follow-up. RH-002 did not rerun the full suite or rebuild the image.

## Tracking record

RH-003 establishes this ledger and the RH-VAL-002 checked-in summary. RH-VAL-003 validates its referenced implementation commits with `git cat-file`, the signature suite XML, JSON parsing and `git diff --check`. Its own commit is resolved from Git by `git log --format="%H %s" --grep="Change-ID: RH-003"`; a file cannot embed the hash of the commit containing itself. Future implementation hashes are recorded in the next tracking update using the same approach.

RH-005 adds delivery leases, queue/worker observability, opt-in retention, Flyway migration/adoption, legacy-event reconciliation, a reference egress policy, and reproducible packaged restart/backup checks. Its commit and final RH-VAL-005 artifact identities are recorded by the following RH-006 tracking update so that the implementation commit hash is known before recording it.

## Latest verified artifact (RH-VAL-005)

- Local image: `relayhub:gap-closure`.
- Image ID: `sha256:0b5abbec1a7903cf4c09ecfa81378be2e1a93b63806ecc617f8e5beb498ceff1`.
- Embedded image JAR SHA-256: `eb7709b4148df53392c834f0afb27804c04db7ed0081eb225199bfd1eb1b3b31`.
- Windows-built JAR SHA-256: `a1ecfac197746c038cb4f52c96a1b00cd1827095032d2b560720c7caa4044d7a` (separately built; not assumed byte-identical to the image JAR).
- [Full evidence](validation/RH-VAL-005.json). Final source-image recovery included 271 additional accepted/delivered events over 60 seconds, no duplicate receiver acceptances observed, and a real logical backup restore.

RH-006 records these final identities and the updated gap status. Resolve its own hash with `git log --format="%H %s" --grep="Change-ID: RH-006"`. No implementation hash is rewritten to make a tracking file refer to itself.
