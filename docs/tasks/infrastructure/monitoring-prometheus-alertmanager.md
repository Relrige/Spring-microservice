---
status: DONE
service: infrastructure
---
# Metrics, Prometheus and Alertmanager with Telegram Notifications

## Context
Group assignment: deploy Prometheus and Alertmanager in the Kubernetes cluster, scrape `/actuator/prometheus` of every service automatically, implement at least one Micrometer metric of each type (Counter, Gauge, Timer, LongTaskTimer, DistributionSummary), configure alerting rules and their handling in Alertmanager, prove in practice that an alert reaches the *Firing* state by simulating a failure or load, and deliver the alert notification to Telegram.
*References:* [Report](../../reports/monitoring-alerting.md), [k8s/monitoring/](../../../k8s/monitoring/), [Network policies](../../../k8s/network/network-policies.yaml)

## Acceptance Criteria
- [x] Every service (all 7 Spring Boot services and the API Gateway) exposes `/actuator/prometheus` (`micrometer-registry-prometheus`, exposure `health,prometheus`); every series carries `application=<spring.application.name>`; HTTP latency is exported with histogram buckets.
- [x] `/actuator/prometheus` is reachable without the internal token (like the health probes), but only from inside the cluster: the gateway does not route `/actuator/**`, and the network policies allow only Prometheus as an extra client.
- [x] Prometheus and Alertmanager run as plain manifests in `k8s/monitoring/` (namespace `voltstore`, pinned images, small resource limits, ClusterIP only, not behind the Ingress).
- [x] Scraping is automatic: Kubernetes pod discovery plus the annotations `prometheus.io/scrape|path|port` on each Deployment. A new service only needs the annotations.
- [x] Business metrics, one of each type: Counter, Timer and DistributionSummary in Payment, Gauge in Catalog, LongTaskTimer in Delivery (see the report, §2). Unit tests cover the payment metrics.
- [x] Alerting rules for availability, HTTP errors and latency, business signals and JVM heap; each has `severity` and `application`.
- [x] Alertmanager groups by alert and service, routes critical alerts faster, inhibits a service's warnings while it is down, and notifies Telegram (with resolved notifications). Bot token and chat id live only in a git-ignored Secret.
- [x] An alert was driven to *Firing* by a simulated failure and by load, and the Telegram request was captured (report, §5).

## Technical Notes / Constraints
- **Why plain manifests, not the kube-prometheus-stack Helm chart / Prometheus Operator:** the repository has no Helm, and the operator adds CRDs (`ServiceMonitor`, `PrometheusRule`) that hide exactly what the assignment is about. Moving to the operator later is a mechanical change: the rules file becomes a `PrometheusRule`, the annotations become a `PodMonitor`.
- **Why the `voltstore` namespace:** pod discovery needs only a namespaced `Role`, and the network policies select Prometheus by pod label without a `namespaceSelector`.
- **Alertmanager config is a Secret** because the Telegram receiver needs the bot token inline next to the integer `chat_id`; it follows the `*-secret-template.yaml` convention (placeholders committed, real values in `k8s/monitoring/secret.yaml`, git-ignored).
- **Known limitations:** Prometheus and Alertmanager use `emptyDir` (data lost with the pod); single Alertmanager (no clustering); thresholds are tuned for the study cluster.
