---
status: DONE
service: api-gateway
---
# Point Ingress at the Gateway and Enforce the Network Trust Boundary

## Context
Trusted headers are only safe if clients cannot reach services except through the gateway. Today the Ingress routes directly to individual services (and rewrites paths). This task makes the gateway the only external entry point and closes the network path to everything else.
*References:* [API Gateway Spec — Trust Boundary](../../design/services-specs/api-gateway-spec.md#trust-boundary), [Security Model — Preconditions](../../design/services-requirements/security-model.md#preconditions-otherwise-the-model-is-unsafe), [Trusted-Header Authentication](../auth-service/08-trusted-header-authentication.md)

## Acceptance Criteria
- [x] The Ingress (`k8s/ingress/ingress.yaml`) has a single rule forwarding all paths to the `api-gateway` Service; all per-service paths are removed.
- [x] The `rewrite-target` and regex annotations are removed: paths reach the gateway, and then the services, unchanged.
- [x] All services remain `ClusterIP` (no NodePort/LoadBalancer, no Ingress rule for individual services).
- [x] Add `NetworkPolicy` resources: each service (auth, catalog, order, inventory, delivery, payment) accepts ingress only from the gateway pods and from other service pods in the namespace; each database accepts ingress only from its own service; the gateway accepts ingress from the Ingress controller.
- [x] Pods carry the labels the policies select on (e.g. `app.kubernetes.io/part-of: voltstore` and a component label for services vs. databases), set in each Deployment's pod template.
- [x] `k8s.txt` and the minikube runbooks (`e2e/manual/k8s-*.md`) start minikube with a policy-capable CNI and say that switching CNI requires recreating the cluster.
- [x] Manual verification, as steps of the `e2e/manual/k8s-gateway.md` runbook ([task 09](09-end-to-end-verification.md)) with results in the task notes: from outside the cluster only the gateway is reachable; a request sent directly to a service from a pod that is neither the gateway nor a service is blocked; the same request from the gateway pod succeeds; `POST /payments/charge` through the Ingress returns `404`.

## Technical Notes / Constraints
- **NetworkPolicy is enforced only if the CNI supports it.** The default minikube setup does not; start minikube with a policy-capable CNI (e.g. `--cni=calico`) and note it in `k8s.txt`, otherwise the policies are silently ignored.
- Payment currently has an Ingress path; it loses its external path on purpose (the gateway does not route it).
- A common pod label (e.g. `app.kubernetes.io/part-of: voltstore`) on service pods keeps the "from other services" rule short.
- TLS termination at the Ingress is optional for this project; if skipped, state so in the documentation.
- Kafka also needs ingress from the services (producers and consumers); include it in the policies or leave Kafka without a policy and say so.
- `kubectl port-forward` traffic is not subject to NetworkPolicy, so the existing Catalog ↔ Inventory runbooks keep working.

## Progress Notes (2026-10-14)
- `k8s/ingress/ingress.yaml`: one `Prefix /` rule to `api-gateway:8080`; `use-regex`/`rewrite-target` and all per-service paths (including `/payment`) removed. TLS is not terminated (local HTTP via `minikube tunnel`); stated in the manifest.
- Pod labels `app.kubernetes.io/part-of: voltstore` and `app.kubernetes.io/component` (`gateway` | `service` | `database` | `broker`) on every Deployment/StatefulSet pod template.
- `k8s/network/network-policies.yaml`: default deny ingress for the namespace; gateway ← namespace `ingress-nginx` on 8080; services ← gateway and services on 8080; Kafka ← services on 9092; each Postgres ← only its own service (`app: <name>-service`) on 5432. Egress is not restricted. Kubelet probes are not affected (Calico allows host → pod).
- `k8s.txt` starts minikube with `--cni=calico`; the runbook explains the profile alternative that keeps an existing cluster.
- **Verified on 2026-10-14** (fresh minikube profile with Calico; images built by Compose; runbook [`e2e/manual/k8s-gateway.md`](../../../e2e/manual/k8s-gateway.md)):
  - Through the Ingress: login `200`, `GET /catalog/categories` `200`, `POST /payments/charge` `404`, `/actuator/health` `404`, old `/payment/...` path `404`, `GET /inventory/stock` without token `401`.
  - Full flow through the Ingress under the policies: admin creates staff, manager creates category/product, `ProductCreated` reached Inventory, worker sets packaging and stock, activation (Catalog → Inventory readiness, service to service) `200`, guest sees `IN_STOCK`.
  - Unlabelled pod: catalog, inventory, auth **and the gateway** time out (`000`); Catalog Postgres and Kafka blocked. Pod labelled `component=gateway`: catalog `200`. Pod labelled `component=service`: Catalog Postgres blocked, Kafka reachable, Inventory reached (`401`).
  - `kubectl port-forward` to a service still works (not filtered), so the Catalog ↔ Inventory runbooks are unaffected.
  - Services restarted 2–3 times on the first start because Postgres was not accepting connections yet ("connection refused", not a policy timeout); this happens without policies too.
