---
status: DONE
service: payment-service
---
# Align Containerization and Kubernetes Deployment

## Context
Payment Service already has a Dockerfile, a Compose entry (port `8085`) and Kubernetes manifests written for the draft. The reworked service adds a Flyway-managed schema, internal-only security, the shared `INTERNAL_TOKEN` and the gateway configuration. This task verifies that it runs in Compose and Kubernetes the same way Catalog and Inventory do.
*References:* [Inventory containerization task](../inventory-service/18-containerization-and-deployment.md), [Catalog containerization task](../catalog-service/20-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [x] The image builds and starts in Docker Compose; Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [x] Secrets follow the split used by the other services: `payment-db-credentials` (database only, used by the app and Postgres pod) and `payment-secrets` (`INTERNAL_TOKEN`, app pod only). Add `k8s/payment/app-secret-template.yaml`.
- [x] `k8s/payment/app-deployment.yaml` loads both secrets and the ConfigMap; no secret values in ConfigMaps or images.
- [x] The ConfigMap sets the gateway settings (`PAYMENT_GATEWAY_PROVIDER=mock`, the mock delay).
- [x] Probes work against Actuator and are not blocked by the security chain.
- [x] `docker-compose.yml` and `.env.example` contain every required variable, with comments.
- [x] Manual smoke test recorded in the PR with the internal token: charge `4242` → `200`; charge again → `409`; charge `0002` on a new order → `402`; charge `5000` → `200` after the internal retry with the same idempotency key (the log shows the timeout and the replayed approval, [task 10](10-gateway-call-resilience.md)); refund the first order → `200`; refund again → `409`; refund an order without a charge → `422`; the same calls without the token → `401`.
- [x] Through the API Gateway, `POST /payments/charge` is **not** reachable (gateway answers `404`).

## Technical Notes / Constraints
- Keep `replicas: 1`: the mock gateway's idempotency-key store is in memory per pod ([task 05](05-payment-gateway-acl-and-mock.md)).
- The host port `8085` in Compose is for local testing only. In Kubernetes the Service stays `ClusterIP` and is not added to the Ingress.
- `NetworkPolicy` (only Order may reach Payment) is a cross-service concern from the Security Model; record it in the PR if it is not in place yet.
- Do not put real-looking card numbers in the PR beyond the mock test numbers.

## Implementation
Done on 2026-10-15:
- `k8s/payment/app-secret-template.yaml`: `payment-secrets` with `INTERNAL_TOKEN` (app pod only; same value as the other services' internal token). `app-deployment.yaml` loads `payment-config`, `payment-db-credentials` and `payment-secrets`, and its comment explains why it must stay at `replicas: 1`. `app-config.yaml` sets `PAYMENT_GATEWAY_PROVIDER`, `_TIMEOUT`, `_MAX_RETRIES`, `_RETRY_DELAY`. The Service stays `ClusterIP` (port `8085`), not in the Ingress.
- `docker-compose.yml` is unchanged: it passes `payment-service/.env` (`env_file`), which now has `INTERNAL_TOKEN` and `PAYMENT_GATEWAY_PROVIDER`; `.env.example` documents every variable.
- **How the Compose check was run:** an isolated Compose project (`voltstore-t09`, a copy of the payment part of `docker-compose.yml` without fixed container names, host port `18085`), so the existing `spring-microservice` containers and volumes were not touched and the database volume started empty. Removed afterwards (`down -v --rmi local`).
- **Startup:** the image built; Flyway applied V1 on an empty Postgres 18 volume; Hibernate `validate` passed; `/actuator/health/readiness` → `200 UP` without any header (the security chain does not block probes).
- **Smoke test (internal token from `.env`):** charge `4242` → `200`; same order again → `409` "Order already charged"; charge `0002` → `402 insufficient_funds`; charge `5000` → `200` after 2.6 s (timeout, then the repeat with the same key got the stored approval, see [task 10](10-gateway-call-resilience.md)); refund of the first order → `200`; again → `409`; refund of an order without a charge → `422`; partial refund → `422`; charge and refund without the token → `401`; wrong token → `401`; unknown route with the token → `403`. Stored rows: `CHARGE/SUCCESS`, `CHARGE/FAILED INSUFFICIENT_FUNDS`, `CHARGE/SUCCESS` (key `:1` for the `5000` order, no `FAILED` row), `REFUND/SUCCESS` (`refund:<orderId>`). None of the full card numbers appears in the container log.
- **API Gateway:** not started for this; its `RouteTableTest#internalEndpoint_isNotFound` already asserts `404` for `POST /payments/charge` and `POST /payments/refund` for every role and for anonymous callers, and passes (57/57).

## Not done here
- **Minikube** was stopped, so the manifests were not applied to a cluster (a client dry-run needs the API server too). To run: create `payment-secrets` from the template with the shared token, rebuild and load the image (`minikube image load voltstore/payment-service:latest`), `kubectl apply -f k8s/payment/`, then repeat the smoke test through `kubectl port-forward svc/payment-service 8085:8085`.
- **Old data:** the existing Compose volume `spring-microservice_payment-service-postgres` (and the minikube PVC `payment-postgres-pvc`, if the draft was deployed there) still holds the draft's `ddl-auto: update` table without a Flyway history, so Flyway will refuse to start on it. It has to be reset once (a destructive step for the team to run): `docker compose rm -sf payment-service payment-service-postgres` and `docker volume rm spring-microservice_payment-service-postgres`; in minikube delete and re-create the PVC.
- **NetworkPolicy** (only Order may reach Payment) is still a cross-service concern from the Security Model; there is none in `k8s/` yet.
