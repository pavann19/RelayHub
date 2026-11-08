# Dependency security verification

RH-010 updates managed HttpCore HTTP/1 and HTTP/2 artifacts to 5.4.3 and snappy-java to 1.1.10.9. The preceding candidate image scan found four High advisories in these transitive dependencies: CVE-2026-54399, CVE-2026-54428, CVE-2026-108106 and CVE-2026-93451. These were fixed before final acceptance; historical RH-007 evidence does not imply a clean vulnerability scan.

Primary release references: [Apache HttpComponents release news](https://hc.apache.org/news.html), [Apache HTTP/1 parser fix](https://github.com/apache/httpcomponents-core/commit/fdc53a32fe0fccf098cc67e71cd125e447c759ed), [Snappy 1.1.10.9 release](https://github.com/xerial/snappy-java/releases/tag/v1.1.10.9), and [Snappy range-validation advisory](https://github.com/xerial/snappy-java/security/advisories/GHSA-xqrx-qh46-34m7).

The workflow scans the built image with Trivy 0.76.1 and fails on any reported High/Critical finding, including advisories without fixes. It exports a CycloneDX inventory and vulnerability report as artifacts. See validation/RH-VAL-010.json for the locally measured final result and artifact identities. Hosted workflow execution remains a separate gate until an authorized remote is configured.

A clean scan is time-bound dependency evidence, not proof of exploit absence, application security certification, or a reason to stop patching. Rebuild from reviewed image digests, rescan before publishing and monitor newly disclosed advisories. TLS verification and application authorization have separate behavioral tests.
