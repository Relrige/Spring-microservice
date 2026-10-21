---
status: DONE
service: infrastructure
---
# Deploy OpenTelemetry Collector, Tempo, Loki and Grafana

## Context
The tracing and logging backends must run in the team's Kubernetes cluster next to Prometheus and Alertmanager, as plain manifests like the rest of `k8s/`.
*References:* [Overview](00-overview.md), [k8s/observability/](../../../../k8s/observability/), [k8s/monitoring/](../../../../k8s/monitoring/), [Network policies](../../../../k8s/network/network-policies.yaml)

## Acceptance Criteria
- [x] New folder `k8s/observability/` (separate from `k8s/monitoring/`, which stays metrics and alerting) with Collector, Tempo, Loki and Grafana manifests, namespace `voltstore`, pinned image versions, small resource requests/limits, `ClusterIP` services only.
- [x] Collector image is **`otel/opentelemetry-collector-contrib:0.130.0`** (the `tail_sampling` processor and the `filelog`/`k8sattributes` components are not in the core distribution).
- [x] Collector topology (overview, decision 1): gateway `otel-collector` Deployment, 1 replica, OTLP 4317/4318 → Tempo; agent `otel-agent` DaemonSet with read-only `hostPath` `/var/log/pods` and a `hostPath` offset directory (`file_storage`), RBAC as a namespaced Role (`get/list/watch` on pods and replicasets) for `k8sattributes`.
- [x] Tempo `2.8.1` in monolithic mode, local storage, OTLP gRPC receiver, 48 h retention; Loki `3.5.3` in single-binary mode, filesystem storage (TSDB, schema v13), OTLP ingestion (`/otlp`), structured metadata on, 48 h retention.
- [x] Grafana `12.1.0` with data sources (Prometheus, Tempo, Loki, fixed uids) provisioned from a ConfigMap; admin account from the `grafana-admin` Secret following the `*-secret-template.yaml` convention; reached with `kubectl port-forward`, not through the Ingress.
- [x] Network policies: services and gateway → collector (4317/4318); collector → Tempo (4317); agent → Loki; Grafana → Tempo, Loki, Prometheus (`prometheus-from-grafana` is new: Prometheus accepted no traffic before); Prometheus → the metrics ports. Nothing else may reach Tempo/Loki, so no service can bypass the tail sampling.
- [x] Collector (gateway and agent), Tempo and Loki expose their own metrics and Prometheus scrapes them (annotations, like the services).
- [x] Startup check documented in [k8s.txt](../../../../k8s.txt) and run on minikube (results below).

## Verification (minikube, 2026-10-21)
- Configs validated with the pinned images before deploying: `otelcol-contrib validate` (gateway and agent), `loki -verify-config`, Tempo started and reported `/ready`.
- `kubectl apply -f k8s/observability/` + network policies: all 5 pods `Running`.
- A test span posted over OTLP HTTP to `otel-collector:4318` was returned by Tempo's `/api/v2/traces/<id>`.
- Loki has streams for the pods that logged since the agent started (`service_name` from the pod label `app`, plus `k8s_*` attributes); a restarted `auth-service` appeared within seconds.
- Prometheus targets `otel-collector`, `otel-agent`, `tempo`, `loki`: `up`.
- Grafana (through the network policies): Prometheus and Loki health OK, Tempo reachable through the data-source proxy.
- Early `Exporting failed ... will retry` lines in the collectors are from the first minute, before Tempo and Loki were ready; retries delivered the data.

## Technical Notes / Constraints
- **Runtime:** the minikube node runs containerd, so `/var/log/pods` holds the files themselves. With the Docker runtime they are symlinks into `/var/lib/docker/containers`, which then needs a second read-only mount (noted in the DaemonSet).
- **The agent runs as root (read-only root filesystem):** the kubelet's log files are readable by root only.
- **`start_at: end`** applies only to files present at the agent's very first start; files of new pods are read from the beginning, and after a restart the saved offsets are used. Idle pods therefore appear in Loki only once they log something.
- **Storage:** `emptyDir` for Tempo, Loki and Grafana (same limitation as Prometheus); a PVC is the production answer.
- **Not yet configured (later tasks):** tail sampling (04), parsing of the ECS JSON lines and severity (05), span → logs links in Grafana (06).
