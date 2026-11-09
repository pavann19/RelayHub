# Repository publication

Repository: https://github.com/pavann19/RelayHub

The existing local history is published to main with its commit hashes preserved. The pre-publication baseline is 0c2140577dd0ff0df3ed404b197e1e260f3b2599. All 17 existing commits and their tracked file versions were checked for prohibited attribution, author/committer metadata and co-author trailers; no matches were found. Publication commit: df5deca04011833b24b6b89bc91dc027f1dd82a7.

The full-history secret scan identified nine historical documentation/demo/test fixtures. Each was inspected against its original source and recorded by exact commit/file/rule/line fingerprint in .gitleaksignore. The reviewed scan reports no remaining findings. No general path or secret-pattern exemption was introduced. Generated private keys, certificates and raw local reports remain excluded from version control; the pre-existing next_task.txt remains untracked.

## Hosted verification

[Run 42561365249](https://github.com/pavann19/RelayHub/actions/runs/42561365249) passed for commit df5deca04011833b24b6b89bc91dc027f1dd82a7. It ran 84 Java tests with no failures/errors/skips, the nine Python tests, both image builds, both High/Critical vulnerability gates, and packaged process-kill/replacement plus logical backup restore. The test-results artifact ID is 13648599966; the exact report hashes and hosted image identities are in validation/RH-VAL-016.json.

The first run flagged deprecated workflow actions. RH-016 pins supported Node 24 releases by full commit SHA: checkout v7.0.1, setup-java v6.2.0 and upload-artifact v7.0.2. The follow-up workflow run is visible on the repository's Actions page. The recorded success above belongs to its exact named commit; it is not a claim about an unrun revision.

Local performance, Kind/Calico and long-duration scope remain separate from hosted verification. No managed production, multi-node availability or application deployment is inferred from publishing this repository or passing CI.

Change and validation IDs remain in CHANGE_LEDGER.md. RH-015 tracks publication preparation; RH-016 records hosted evidence and the workflow maintenance follow-up.
