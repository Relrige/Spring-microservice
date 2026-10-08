---
status: TODO
service: api-gateway
---
# Containerize and Deploy API Gateway

## Context
The gateway must run next to the other services locally (docker-compose) and in Kubernetes, with its own configuration and secret.
*References:* [API Gateway Spec — Configuration](../../design/services-specs/api-gateway-spec.md#configuration), [Containerize and Deploy Auth Service](../auth-service/07-containerization-and-deployment.md)

## Acceptance Criteria
- [ ] Add a Dockerfile consistent with the other services.
- [ ] Add Kubernetes manifests under `k8s/gateway/` (Deployment, ClusterIP Service `api-gateway`, ConfigMap, Secret template), following the naming, labels, `runAsNonRoot` security context and resource settings of `k8s/auth/`. No database manifests.
- [ ] `JWT_SECRET` comes from a Kubernetes Secret (`gateway-secrets` template), never from the image or the ConfigMap. Upstream URLs come from the ConfigMap.
- [ ] Liveness and readiness probes use Actuator health.
- [ ] Add the gateway to `docker-compose.yml` (published on a free host port such as `8080`; `JWT_SECRET` in `api-gateway/.env` with an `.env.example`; upstream URLs pointing at compose service names) and to the build/load commands in `k8s.txt`.
- [ ] The gateway starts without waiting for its upstreams (it is stateless).

## Technical Notes / Constraints
- Inspect `k8s/` first and reuse its layout instead of inventing a new one.
- The same `JWT_SECRET` value must exist in two Secrets (`auth-secrets` and `gateway-secrets`). Say so in the secret template.
- Ingress and NetworkPolicy changes are a separate task: [08](08-ingress-and-trust-boundary.md).
