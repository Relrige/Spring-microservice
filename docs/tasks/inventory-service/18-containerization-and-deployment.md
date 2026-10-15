---
status: DONE
service: inventory-service
---
# Align Containerization and Kubernetes Deployment

## Context
Inventory Service already has a Dockerfile, Compose entry and Kubernetes manifests written for the draft. The reworked service adds Flyway-managed schema, security configuration, the shared `INTERNAL_TOKEN` and the Kafka connection. This task verifies that the complete service apart from the Catalog call (tasks 01–17) runs in Compose and Kubernetes the same way auth-service does.
*References:* [Auth Service containerization task](../auth-service/07-containerization-and-deployment.md), [Catalog containerization task](../catalog-service/20-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [x] The image builds and starts in Docker Compose; Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [x] Secrets follow the auth-service split: `inventory-db-credentials` (database only, used by the app and Postgres pod) and `inventory-secrets` (`INTERNAL_TOKEN`, app pod only). Update `secret-template.yaml` and add an app secret template.
- [x] `k8s/inventory/app-deployment.yaml` loads both secrets and the ConfigMap; no secret values in ConfigMaps or images.
- [x] Probes still work against Actuator and are not blocked by the security chain.
- [x] `docker-compose.yml` and `.env.example` contain every required variable (database, `INTERNAL_TOKEN`, Kafka bootstrap servers, topic names), with comments.
- [x] Manual smoke test recorded in the PR with hand-set trusted headers: adjust stock, set packaging, call readiness with the internal token, reserve and release a reservation; confirm events appear on the topic and unknown routes are denied.
- [x] With two replicas running, the outbox relay keeps per-product event order ([task 17](17-transactional-outbox.md)) and both replicas consume `ProductCreated` / `OrderShipped` as one consumer group (each message handled once).

## Technical Notes / Constraints
- With `replicas: 2` and a topic of 3 partitions, partitions are divided between the two consumers of the group; verify this in the broker's consumer-group description.
- Ingress routing is left to the API Gateway work; do not extend the current Ingress.
- `NetworkPolicy` is a cross-service concern from the Security Model; if it is not in place yet, note it in the PR instead of creating a single-service policy.
- `INVENTORY_SERVICE_URL`-style settings for the Catalog client are added in [task 19](19-catalog-product-existence-client.md).

## Starting Point (from tasks 01–14, collected 2026-10-12)
- **Kafka wiring and first minikube run:** moved from here (deferred by [task 05](05-kafka-spring-setup-and-topic.md)) to the infrastructure task [Kafka: Inventory Wiring and First Minikube Run](../infrastructure/kafka-inventory-wiring-and-minikube-check.md). If it is not done yet, do it before this task.
- **Kubernetes startup blocker:** `application.yml` requires `${INTERNAL_TOKEN}` and no Kubernetes secret provides it yet, so the pod fails at startup. The `inventory-secrets` acceptance criterion above fixes it.
- **End-to-end Catalog ↔ Inventory check** (once [task 15](15-consume-product-created.md) and Catalog [task 18](../catalog-service/18-consume-stock-events.md) are done): create a product in Catalog → a `StockItem` appears in Inventory; adjust its stock 0 → 10 → 0 → the product's `stockStatus` in Catalog goes `IN_STOCK` → `OUT_OF_STOCK`. Run it in Compose and in minikube.
- **Deferred to here from [task 06](06-stock-events-publish-and-listen.md):** the manual end-to-end run with a real broker (adjust 0 → 10 → 0, events visible with key, partition and offset). The demo listener is gone after task 15, so read the topic with a console consumer or the broker's CLI instead.
- **Smoke test expectations:** set packaging, readiness, reserve and release answer `204 No Content`; readiness for an unknown product is `404`. The full route/role table is in the [Inventory spec](../../design/services-specs/inventory-service-spec.md) (*Security and routing*).
- Topic-name variables to add: the consumed Catalog and Order topics (tasks 15, 16). The stock-events topic is a code constant and needs no variable. The Catalog topic is `catalog.product-events`; task 15 reads it from `CATALOG_PRODUCT_EVENTS_TOPIC` (default `catalog.product-events`).

## Progress Notes (2026-10-12, done before tasks 16–17)
- **Secrets split done:** `k8s/inventory/app-secret-template.yaml` adds `inventory-secrets` (`INTERNAL_TOKEN`, same value as auth's `AUTH_INTERNAL_TOKEN`); `app-deployment.yaml` loads ConfigMap + `inventory-db-credentials` + `inventory-secrets`; the Postgres pod still gets only the database secret.
- **Variables:** `.env.example` documents `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `CATALOG_PRODUCT_EVENTS_TOPIC` (optional, default `catalog.product-events`) and `INTERNAL_TOKEN`; the ConfigMap sets the bootstrap address and the topic.
- **Compose:** image builds, Flyway applies V1/V2 on an empty volume, readiness `UP`, and the Catalog ↔ Inventory event flow works (see the [infrastructure task](../infrastructure/kafka-inventory-wiring-and-minikube-check.md) notes). The full manual smoke test (packaging, readiness with internal token, reserve/release, denied routes) is not recorded yet.
- **Minikube (2026-10-13):** the service runs in minikube with the secrets split, connects to Kafka, consumes `ProductCreated` and publishes stock events; scaled to 2 replicas, both pods shared the partitions of `catalog.product-events` in group `inventory-service` (see the [infrastructure task](../infrastructure/kafka-inventory-wiring-and-minikube-check.md)).
- **Open:** the manual smoke test above (packaging, readiness with the internal token, reserve/release, denied routes) and the outbox criterion (task 17 not done yet).

## Progress Notes (2026-10-13, after merging main)
- Main brought Inventory tasks 15 (reworked: `@RetryableTopic` + DLT + `processed_events`) and 16 (`OrderShipped` consumer), and Catalog tasks 18 (stock-event consumer) and 20–22. The end-to-end prerequisite above is therefore met; the manual runbooks in [e2e/manual](../../../e2e/manual/README.md) were updated for it (Catalog's trusted headers, outbox, `stockStatus` check, the new retry/DLT topics) but **not re-run** yet.
- **Shared token:** Catalog reads `AUTH_INTERNAL_TOKEN` (`catalog-secrets`), Inventory `INTERNAL_TOKEN` (`inventory-secrets`). The names differ, the values must be equal (Catalog calls Inventory's readiness endpoint). Aligning the variable name is a cross-service clean-up, not done here.
- **Topic variables still to add:** `inventory.kafka.topics.order-events` is hard-coded (`order.order-events`, provisional) in `application.yml`; give it an environment variable (for example `ORDER_EVENTS_TOPIC`) in `.env.example` and `k8s/inventory/app-config.yaml` together with the Catalog topic's `CATALOG_PRODUCT_EVENTS_TOPIC`.
- **Topics now created by Inventory** besides `inventory.stock-events`: `catalog.product-events-inventory-retry-0/1/2`, `catalog.product-events-inventory-dlt` (`@RetryableTopic`, 3 partitions each; it also creates `catalog.product-events` if Catalog has not yet) and `inventory.order.order-events.dlt`. With 2 replicas, check that each of them is shared within group `inventory-service`.

## Implementation Notes (2026-10-14, after tasks 17, 19 and 20)
- **Topic variable:** `inventory.kafka.topics.order-events` now reads `ORDER_EVENTS_TOPIC` (default `order.order-events`), documented in `.env.example` and set in `k8s/inventory/app-config.yaml` next to `CATALOG_PRODUCT_EVENTS_TOPIC`. Task 19 added `CATALOG_SERVICE_URL` there too (`http://catalog-service:8081` in Kubernetes); task 20's optional tuning variables are listed commented out in `.env.example`.
- **How the Compose check was run:** in an isolated Compose project (`docker compose -p voltstore-t18` on a copy of the Compose configuration with the fixed `container_name`s removed), so the existing `spring-microservice` containers and volumes were not touched and every volume started empty. `.env` files were copied from the `.env.example` files. A second Inventory replica (`inventory-service-2`, same image and env, no host port) was added for the two-replica checks. The project was removed afterwards (`down -v --rmi local`).
- **Startup:** Flyway applied V1–V4 on an empty Postgres 18 volume, Hibernate `validate` passed, Catalog and Inventory readiness `UP` (probes answer without headers, so the security chain does not block them).
- **Smoke test (one replica, hand-set trusted headers):** a product created in Catalog got an empty StockItem via `ProductCreated`; `adjust +10` → `200 {"newAvailableQuantity":10}`; set packaging → `204`; readiness with the internal token → `204`; readiness for an unknown product → `404`; readiness without a token → `401`; reserve 10 → `204`; release → `204`; adjust of a product unknown to Catalog → `404` "not found in Catalog" (the task 19 check against the real Catalog); unknown route and a worker calling an internal route → `403`. Outbox rows 1–3 (`Replenished`, `Depleted`, `Replenished`) all published; the topic showed them in partition 0 at offsets 0–2 with headers `eventType` and `eventId` and key = productId; Catalog's `stockStatus` ended `IN_STOCK`.
- **Two replicas:** the consumer group `inventory-service` split the 3 partitions of `catalog.product-events` (and of each retry/DLT topic) 2 + 1 between the replicas. Six new products were each handled exactly once (6 StockItems; "StockItem created" logged 2× by one replica and 4× by the other). Then 5 rounds of `+3`/`−3` for the 6 products, sent in parallel: 0 unpublished outbox rows afterwards, and for every product the 10 events on the topic were alternating `Replenished`/`Depleted` in exactly the outbox `sequence_number` order, with both replicas' relays competing for the advisory lock.
- **Not done here:** minikube was not re-run for these changes (it ran with the secrets split on 2026-10-13, see the progress notes above); rebuild and load the image, apply `k8s/inventory/`, and repeat the checks there with `kubectl scale deployment inventory-service --replicas=2`. The Deployment stays at `replicas: 1` for the small local cluster.
- **NetworkPolicy:** there is none in `k8s/` yet. It is a cross-service concern from the Security Model; record it in the PR instead of adding a single-service policy.
- **Compose observation:** Compose's `env_file` gives the Postgres container the whole `inventory-service/.env`, including `INTERNAL_TOKEN`. Kubernetes already separates the two secrets; splitting the Compose env files (database vs. app) would mirror it and is a possible cross-service clean-up.
- **Manual runbooks:** the [Compose runbook](../../../e2e/manual/docker-compose-catalog-inventory.md) still uses fixed container names; its steps match what was checked here.
