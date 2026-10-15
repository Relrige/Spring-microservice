---
status: TODO
service: payment-service
---
# Align Containerization and Kubernetes Deployment

## Context
Payment Service already has a Dockerfile, a Compose entry (port `8085`) and Kubernetes manifests written for the draft. The reworked service adds a Flyway-managed schema, internal-only security, the shared `INTERNAL_TOKEN` and the gateway configuration. This task verifies that it runs in Compose and Kubernetes the same way Catalog and Inventory do.
*References:* [Inventory containerization task](../inventory-service/18-containerization-and-deployment.md), [Catalog containerization task](../catalog-service/20-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [ ] The image builds and starts in Docker Compose; Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [ ] Secrets follow the split used by the other services: `payment-db-credentials` (database only, used by the app and Postgres pod) and `payment-secrets` (`INTERNAL_TOKEN`, app pod only). Add `k8s/payment/app-secret-template.yaml`.
- [ ] `k8s/payment/app-deployment.yaml` loads both secrets and the ConfigMap; no secret values in ConfigMaps or images.
- [ ] The ConfigMap sets the gateway settings (`PAYMENT_GATEWAY_PROVIDER=mock`, the mock delay).
- [ ] Probes work against Actuator and are not blocked by the security chain.
- [ ] `docker-compose.yml` and `.env.example` contain every required variable, with comments.
- [ ] Manual smoke test recorded in the PR with the internal token: charge `4242` → `200`; charge again → `409`; charge `0002` on a new order → `402`; charge `5000` → `200` after the internal retry with the same idempotency key (the log shows the timeout and the replayed approval, [task 10](10-gateway-call-resilience.md)); refund the first order → `200`; refund again → `409`; refund an order without a charge → `422`; the same calls without the token → `401`.
- [ ] Through the API Gateway, `POST /payments/charge` is **not** reachable (gateway answers `404`).

## Technical Notes / Constraints
- Keep `replicas: 1`: the mock gateway's idempotency-key store is in memory per pod ([task 05](05-payment-gateway-acl-and-mock.md)).
- The host port `8085` in Compose is for local testing only. In Kubernetes the Service stays `ClusterIP` and is not added to the Ingress.
- `NetworkPolicy` (only Order may reach Payment) is a cross-service concern from the Security Model; record it in the PR if it is not in place yet.
- Do not put real-looking card numbers in the PR beyond the mock test numbers.
