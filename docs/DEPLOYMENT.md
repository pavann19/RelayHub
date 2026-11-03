# RelayHub — Deployment & Observability Guide

RelayHub is cloud-native and supports single-container standalone deployment, Docker Compose local topology, and enterprise Kubernetes orchestration.

---

## 1. Quick Local Start (Docker Compose)

The provided Compose configuration spins up the complete platform topology:
- **PostgreSQL 16**: Port 5432 (persistence & audit)
- **Redis 7**: Port 6379 (token bucket rate limiting)
- **Apache Kafka 3.7 (KRaft mode)**: Port 9092 (event bus & DLQ)
- **RelayHub Platform**: Port 8080 (REST Gateway & Dispatcher)
- **Mock Webhook Receiver**: Port 9999 (echo target capturing inbound deliveries)
- **Prometheus**: Port 9090 (metrics collection)
- **Grafana**: Port 3000 (pre-provisioned observability dashboards)

```bash
# Build and launch complete topology
docker compose -f deploy/docker-compose.yml up --build -d

# Verify all services are healthy
docker compose -f deploy/docker-compose.yml ps
```

### Accessing Local Services
- **RelayHub Gateway**: `http://localhost:8080`
- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **Prometheus UI**: `http://localhost:9090`
- **Grafana Dashboards**: `http://localhost:3000` (User: `admin` / Password: `admin`)
- **Mock Webhook Target**: `http://localhost:9999`

---

## 2. Kubernetes Deployment (`deploy/k8s`)

Deploy RelayHub to any CNCF-certified Kubernetes cluster (GKE, EKS, AKS, Minikube, k3s):

```bash
# 1. Create Namespace, ConfigMap, and Secrets
kubectl apply -f deploy/k8s/00-namespace.yaml
kubectl apply -f deploy/k8s/01-configmap.yaml
kubectl apply -f deploy/k8s/02-secret.yaml

# 2. Deploy Infrastructure Dependencies
kubectl apply -f deploy/k8s/03-postgres.yaml
kubectl apply -f deploy/k8s/04-redis.yaml
kubectl apply -f deploy/k8s/05-kafka.yaml

# 3. Deploy RelayHub Service and HPA
kubectl apply -f deploy/k8s/06-relayhub-deployment.yaml
kubectl apply -f deploy/k8s/07-relayhub-service.yaml
kubectl apply -f deploy/k8s/08-hpa.yaml

# 4. Monitor rollout status
kubectl rollout status deployment/relayhub -n relayhub
```

### Horizontal Pod Autoscaling (HPA)
RelayHub autoscales from 3 to 15 pods based on CPU utilization (threshold: 70%) and memory consumption (threshold: 80%).

---

## 3. Observability & Telemetry

### Prometheus Metrics
RelayHub exports native Micrometer metrics at `/actuator/prometheus`:
- `relayhub_gateway_requests_total`: Total inbound API requests by tenant, method, status code.
- `relayhub_ratelimit_exceeded_total`: Total requests blocked by rate limits or monthly quotas.
- `relayhub_webhook_deliveries_total`: Total outbound dispatches tagged with `outcome` (`SUCCESS`, `RETRY`, `DEAD_LETTER`).
- `relayhub_webhook_delivery_latency_seconds`: Delivery roundtrip latency distribution (p50, p95, p99).
- `relayhub_dlq_events_total`: Total events routed to dead-letter queue.

### Distributed Tracing (W3C Trace Context)
Every outbound webhook includes the `traceparent` header conforming to W3C Trace Context specifications, linking the originating API call, Kafka message envelope, and outbound webhook delivery across distributed tracing backends (OpenTelemetry, Jaeger, Datadog).

