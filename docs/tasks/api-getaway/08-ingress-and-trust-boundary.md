---
status: TODO
service: api-gateway
---
# Point Ingress at the Gateway and Enforce the Network Trust Boundary

## Context
Trusted headers are only safe if clients cannot reach services except through the gateway. Today the Ingress routes directly to individual services (and rewrites paths). This task makes the gateway the only external entry point and closes the network path to everything else.
*References:* [API Gateway Spec — Trust Boundary](../../design/services-specs/api-gateway-spec.md#trust-boundary), [Security Model — Preconditions](../../design/services-requirements/security-model.md#preconditions-otherwise-the-model-is-unsafe), [Trusted-Header Authentication](../auth-service/08-trusted-header-authentication.md)

## Acceptance Criteria
- [ ] The Ingress (`k8s/ingress/ingress.yaml`) has a single rule forwarding all paths to the `api-gateway` Service; all per-service paths are removed.
- [ ] The `rewrite-target` and regex annotations are removed: paths reach the gateway, and then the services, unchanged.
- [ ] All services remain `ClusterIP` (no NodePort/LoadBalancer, no Ingress rule for individual services).
- [ ] Add `NetworkPolicy` resources: each service (auth, catalog, order, inventory, delivery, payment) accepts ingress only from the gateway pods and from other service pods in the namespace; each database accepts ingress only from its own service; the gateway accepts ingress from the Ingress controller.
- [ ] Pods carry the labels the policies select on (e.g. `app.kubernetes.io/part-of: voltstore` and a component label for services vs. databases), set in each Deployment's pod template.
- [ ] `k8s.txt` and the minikube runbooks (`e2e/manual/k8s-*.md`) start minikube with a policy-capable CNI and say that switching CNI requires recreating the cluster.
- [ ] Manual verification, as steps of the `e2e/manual/k8s-gateway.md` runbook ([task 09](09-end-to-end-verification.md)) with results in the task notes: from outside the cluster only the gateway is reachable; a request sent directly to a service from a pod that is neither the gateway nor a service is blocked; the same request from the gateway pod succeeds; `POST /payments/charge` through the Ingress returns `404`.

## Technical Notes / Constraints
- **NetworkPolicy is enforced only if the CNI supports it.** The default minikube setup does not; start minikube with a policy-capable CNI (e.g. `--cni=calico`) and note it in `k8s.txt`, otherwise the policies are silently ignored.
- Payment currently has an Ingress path; it loses its external path on purpose (the gateway does not route it).
- A common pod label (e.g. `app.kubernetes.io/part-of: voltstore`) on service pods keeps the "from other services" rule short.
- TLS termination at the Ingress is optional for this project; if skipped, state so in the documentation.
- Kafka also needs ingress from the services (producers and consumers); include it in the policies or leave Kafka without a policy and say so.
- `kubectl port-forward` traffic is not subject to NetworkPolicy, so the existing Catalog ↔ Inventory runbooks keep working.
