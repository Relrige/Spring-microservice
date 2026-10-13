---
status: TODO
service: inventory-service
---
# Align Containerization and Kubernetes Deployment

## Context
Inventory Service already has a Dockerfile, Compose entry and Kubernetes manifests written for the draft. The reworked service adds Flyway-managed schema, security configuration, the shared `INTERNAL_TOKEN` and the Kafka connection. This task verifies that the complete service apart from the Catalog call (tasks 01–17) runs in Compose and Kubernetes the same way auth-service does.
*References:* [Auth Service containerization task](../auth-service/07-containerization-and-deployment.md), [Catalog containerization task](../catalog-service/20-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [ ] The image builds and starts in Docker Compose; Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [ ] Secrets follow the auth-service split: `inventory-db-credentials` (database only, used by the app and Postgres pod) and `inventory-secrets` (`INTERNAL_TOKEN`, app pod only). Update `secret-template.yaml` and add an app secret template.
- [ ] `k8s/inventory/app-deployment.yaml` loads both secrets and the ConfigMap; no secret values in ConfigMaps or images.
- [ ] Probes still work against Actuator and are not blocked by the security chain.
- [ ] `docker-compose.yml` and `.env.example` contain every required variable (database, `INTERNAL_TOKEN`, Kafka bootstrap servers, topic names), with comments.
- [ ] Manual smoke test recorded in the PR with hand-set trusted headers: adjust stock, set packaging, call readiness with the internal token, reserve and release a reservation; confirm events appear on the topic and unknown routes are denied.
- [ ] With two replicas running, the outbox relay keeps per-product event order ([task 17](17-transactional-outbox.md)) and both replicas consume `ProductCreated` / `OrderShipped` as one consumer group (each message handled once).

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
