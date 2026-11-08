# Isolated local acceptance

Use a dedicated cluster, never an existing project or production context. `validate-production.py` checks the exact single-node cluster name before provisioning. It refuses an existing application namespace on `--deploy`, uses only its explicit kubeconfig/context, and stores generated credentials under ignored `target/readiness/` with a private ACL. Keep that directory private; checked-in evidence consists of redacted summaries and hashes.

Prerequisites: Docker, Kind, kubectl, JDK 21 keytool and `pip install -r deploy/validation/requirements.txt`. Build the source image using `docker build --provenance=false --platform linux/amd64 -t relayhub:release-candidate .`.

This is a pinned compatibility fixture, not the recommended version set for a new hosted cluster. Use a kubectl client within the server's supported minor-version skew. Windows scripts prefer target/readiness/bin/kubectl.exe when provided; download it from the official Kubernetes release endpoint and verify its published SHA-256 before use. Target-environment acceptance must use supported Kubernetes/CNI releases.

Build the minimal patched TLS edge using `docker build --provenance=false --platform linux/amd64 -f deploy/edge/Dockerfile -t relayhub-edge:release-candidate .`. Load both locally built images into Kind; the edge no longer uses the superseded stock image that failed the dependency gate.

```powershell
New-Item -ItemType Directory -Force target/readiness
kind create cluster --name relayhub-validation --image kindest/node:v1.31.0 --config deploy/validation/kind.yaml --kubeconfig target/readiness/kubeconfig --wait 0s
Invoke-WebRequest https://raw.githubusercontent.com/projectcalico/calico/v3.29.3/manifests/calico.yaml -OutFile target/readiness/calico.yaml
kubectl --kubeconfig target/readiness/kubeconfig --context kind-relayhub-validation apply -f target/readiness/calico.yaml
kubectl --kubeconfig target/readiness/kubeconfig --context kind-relayhub-validation -n kube-system rollout status daemonset/calico-node --timeout=60s
kind load docker-image relayhub:release-candidate --name relayhub-validation
kind load docker-image relayhub-edge:release-candidate --name relayhub-validation
python scripts/validate-production.py --deploy --image relayhub:release-candidate --seconds 600 --rate 5
python scripts/validate-alerts.py
python scripts/measure-capacity.py --rates 5,10,20,50 --seconds 60
python scripts/validate-bootstrap.py relayhub:release-candidate
python scripts/verify-packaged-recovery.py --image relayhub:release-candidate --output target/readiness/packaged-recovery.json
```

If rollout takes longer than 60 seconds, inspect status and repeat the bounded wait. Infrastructure images can pull normally when no cached platform image is available. Older Kind/containerd combinations may report an error importing a multi-platform Docker archive even after importing its amd64 image; inspect `crictl images`, pod image IDs and actual running artifacts before accepting a result. Do not assume an import command's partial output is success.

For a fresh rerun, preserve the previous reports and move the old generated credentials.private.json to a private archive before `--deploy`; it intentionally refuses to overwrite that file. A failed run is not a passing report. Reusing an already deployed fixture omits `--deploy` and must retain exactly one matching subscription; use a fresh namespace for independent full baselines.

The fixture private CA exists only for the local HTTPS edge/receiver. Production private targets are explicitly allowed only in this test overlay, with a corresponding narrow CNI exception. TLS verification remains enabled. NGINX uses the same hardened edge manifest; the local overlay has one edge replica. PostgreSQL is single-instance, Kafka is disabled for this drill, and HPA is not validated. The Java integration suite separately exercises real Kafka.

Tests inject a 12-second receiver outage, replace one application pod, compare acceptances with database/receiver state, exercise exact idempotency replay and mismatch, and prove private egress deny/allow/deny. Alert validation injects one disposable poison record and verifies both firing and resolved webhook receipts. Capacity measurement preserves each RPC and verifies stored payload sequences, completion counts, receiver counts and backlog drain.

After preserving redacted evidence, clean up only the dedicated cluster:

```powershell
kind delete cluster --name relayhub-validation
```

This is local acceptance, not hosted CI, multi-node availability, long-term endurance, managed backup certification or a production capacity guarantee.
