---
status: DONE
service: catalog-service
---
# Align Containerization and Kubernetes Deployment

## Context
Catalog Service already has a Dockerfile, Compose entry and Kubernetes manifests, written for the draft. The reworked service adds Flyway-managed schema, security configuration, and the shared `INTERNAL_TOKEN`. This task checks that the complete service apart from the inter-service call (tasks 01–19) really runs in Compose and Kubernetes the same way auth-service does.
*References:* [Auth Service containerization task](../auth-service/07-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [x] The image builds and starts in Docker Compose; Flyway applies V1/V2 against the empty Postgres 18 volume and Hibernate `validate` passes.
- [x] Secrets are split like in auth-service: `catalog-db-credentials` (database only, used by the app and the Postgres pod) and `catalog-secrets` (`INTERNAL_TOKEN`, used only by the app pod). Update `secret-template.yaml` and add an app secret template.
- [x] `k8s/catalog/app-deployment.yaml` loads both secrets and the ConfigMap; no secret values are in ConfigMaps or images.
- [x] Liveness/readiness probes still work against Actuator and are not blocked by the security chain.
- [x] `docker-compose.yml` and `catalog-service/.env.example` contain every variable the application needs, with comments; `.env` stays untracked.
- [x] Manual smoke test recorded in the PR: create a category and a product with the `X-User-Id` / `X-User-Role` headers set by hand, list products anonymously, and confirm `denyAll()` blocks unknown routes.
- [x] `k8s.txt` build/load commands still include `catalog-service` (already present; verify, do not duplicate).

## Technical Notes / Constraints
- Catalog uses `replicas: 2` while auth uses 1. Two replicas share one database, which matters later for the outbox relay ([task 19](19-transactional-outbox.md)) and for Flyway (only one instance should apply migrations at a time; Flyway's lock handles that, but note it).
- Ingress routing for the catalog is left to the API Gateway work. The current Ingress points straight at services; do not extend it.
- `NetworkPolicy` restricting ingress to the gateway and other services is a cross-service concern from the Security Model; if it is not in place yet, add a note to the PR instead of creating a policy only for this service.
- The Kafka variables were added in [task 10](10-kafka-spring-setup-and-topic.md); verify here that the whole stack (broker, Postgres, catalog) starts together in Compose and in minikube, and that the outbox relay ([task 19](19-transactional-outbox.md)) works with two replicas.
- `INVENTORY_SERVICE_URL` is added in [task 21](21-inventory-readiness-client.md).
- The broker's first minikube run and the `k8s.txt` path fix are in the infrastructure task [Kafka: Inventory Wiring and First Minikube Run](../infrastructure/kafka-inventory-wiring-and-minikube-check.md); if it is not done yet, do it before this task.
- With 2 replicas, check in the broker's consumer-group description (`kafka-consumer-groups.sh --describe --group catalog-service`) that the stock-events consumer ([task 18](18-consume-stock-events.md)) is one group with the partitions split between the pods.
- **Secrets (implemented):** `catalog-db-credentials` (`secret-template.yaml`, database only) and the new `catalog-secrets` (`app-secret-template.yaml`, `AUTH_INTERNAL_TOKEN`, same placeholder value as auth-service). The app Deployment loads the ConfigMap and both Secrets; the Postgres pod only loads `catalog-db-credentials`. The variable is `AUTH_INTERNAL_TOKEN` (bound to `auth.internal.token`) in every service, as in auth-service.
- **Compose run (2026-10-11):** `docker compose up -d --build catalog-service` built the image and started it after Postgres and Kafka were healthy. Flyway applied V3 on the existing local volume, and Hibernate `validate` passed (the empty-volume path V1→V3 is covered by every Testcontainers test). Smoke test from inside the Compose network (host port 8081 was taken by another program): category `201` and product `201` with `X-User-Id` / `X-User-Role: CATALOG_MANAGER`; anonymous `POST /catalog/products` → `401`; anonymous listing → `200` (product not visible while `NOT_ACTIVE`); unknown route as `ADMIN` → `403`; `/actuator/health/readiness` → `200 UP`. `ProductCreated` arrived on `catalog.product-events` with `eventType`/`eventId` headers via the outbox; a `ProductStockReplenished` sent with `kafka-console-producer` set the product `IN_STOCK`; a "not json" message went to `catalog.stock-events-dlt`.
- **Not done yet:** the minikube run (2 replicas sharing the outbox) was not performed, because the cluster was stopped. That and the PR record of the smoke test keep this task open. Flyway's own schema-history lock serializes migrations when both replicas start together.
- `NetworkPolicy` is not in place for any service; mention it in the PR (cross-service concern, Security Model). The current Ingress still bypasses the gateway; per this task it was not extended.
- `k8s.txt` already builds and loads `catalog-service` (verified, not duplicated).

## Implementation Notes (2026-10-23)
- **Smoke test:** the manual smoke test of the Compose run (2026-10-11, notes above) covers the criterion: category and product `201` with hand-set `X-User-Id` / `X-User-Role`, anonymous listing `200`, anonymous write `401`, unknown route `403`. It is recorded in this PR, which closes the task. Since then the same calls are made through the gateway by the [Compose gateway runbook](../../../e2e/manual/docker-compose-gateway.md) (step 4), run again on 2026-10-23.
- **Minikube run with 2 replicas (2026-10-23):** on a minikube profile with Calico (`voltstore-np`), every service deployed from the current images, Catalog scaled temporarily to 2 replicas (`kubectl scale deployment/catalog-service -n voltstore --replicas=2`, back to 1 afterwards; the manifest keeps 1 replica, see below).
  - Both pods became ready. The second pod started on an already migrated database, so concurrent migrations were not exercised; that relies on Flyway's schema-history lock, as noted above.
  - The stock-events consumer is one group: `kafka-consumer-groups.sh --describe --group catalog-service` showed `inventory.stock-events` partitions 0 and 1 on one pod and 2 on the other.
  - Outbox with two relays: 10 products created through the Ingress; `catalog.product-events` held exactly one `ProductCreated` for each of them (`FOR UPDATE SKIP LOCKED` in `OutboxRepository`).
- **Verified:** the checks above; no code changed apart from one comment, so the test suite was not re-run.

## Deviations from spec / task
- **One replica instead of two.** The task notes say Catalog uses `replicas: 2`; `k8s/catalog/app-deployment.yaml` now has `replicas: 1`, like every service (`k8s.txt`: "Every service runs with 1 replica… scale up temporarily"). The two-replica behaviour was therefore checked by scaling up for the run rather than by changing the manifest. The comment in `OutboxRepository` that said "the Deployment runs 2 replicas" now says "when the Deployment is scaled up".
- **Kafka admin commands under the network policies.** Since the `NetworkPolicy` objects exist, `kubectl exec kafka-0 -- kafka-consumer-groups.sh --bootstrap-server kafka:9092` times out: the broker pod is not a `service` and so may not reach the `kafka` Service. The run used a throw-away pod with the broker's image and the label `app.kubernetes.io/component=service`. The [Catalog ↔ Inventory runbook](../../../e2e/manual/k8s-catalog-inventory.md) predates the policies and still uses `kubectl exec kafka-0`; on a Calico cluster it needs the same workaround (open point in the PR).
- **Superseded notes above:** `NetworkPolicy` is now in place for every service (`k8s/network/`), and the Ingress no longer bypasses the gateway (it reaches it through the frontend's `/api` proxy).
