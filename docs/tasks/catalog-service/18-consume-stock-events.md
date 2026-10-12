---
status: TODO
service: catalog-service
---
# EP-CAT-EVT-01 / EP-CAT-EVT-02: Consume Stock Events

## Context
Inventory Service tells Catalog when a product's available quantity crosses zero. Catalog applies that to the `Product.stockStatus` read-model so product listings never need to call Inventory. Both consumers follow the same pattern and are built together here.

Inventory Service may not exist yet. Tests publish the events themselves, using the payload from the catalogue.
*References:* [Catalog Spec — EP-CAT-EVT-01, EP-CAT-EVT-02](../../design/services-specs/catalog-service-spec.md), [Event Catalogue — `ProductStockReplenished`, `ProductStockDepleted`](../../design/services-requirements/events-catalogue.md), [Kafka setup](10-kafka-spring-setup-and-topic.md)

## Acceptance Criteria
- [ ] `@KafkaListener` consumers for `ProductStockReplenished` (→ `stockStatus = IN_STOCK`) and `ProductStockDepleted` (→ `stockStatus = OUT_OF_STOCK`), payload `{ productId, timestamp }`, in a dedicated consumer group (`catalog-service`).
- [ ] Unknown `productId` → log a warning and acknowledge (the message is not retried and does not block the partition).
- [ ] Re-delivery of the same event, or an event matching the current value, is an idempotent no-op.
- [ ] Malformed or undeserializable payloads do not block the consumer: they are sent to a dead-letter topic (or skipped and logged, if the team prefers) after a bounded number of attempts. The choice is documented.
- [ ] Transient failures (for example the database being briefly unavailable) are retried with a bounded back-off before going to the dead-letter handling.
- [ ] Offsets are committed only after the database update succeeded (default container ack mode is acceptable, but state which mode is used and why).
- [ ] The stock status update changes only the `stockStatus` column and does not overwrite concurrent manager edits (see the optimistic-locking decision in [task 03](03-product-entity-and-persistence.md)).
- [ ] The temporary consumer from [task 11](11-product-created-publish-and-listen.md) is removed.
- [ ] Tests (Testcontainers Kafka + PostgreSQL): replenished then depleted updates the product; unknown product is acknowledged; duplicate event is a no-op; a poison message does not stop later messages from being processed.

## Technical Notes / Constraints
- **Ordering question to settle with the Inventory spec:** if `Replenished` and `Depleted` are on different topics, Kafka gives no ordering guarantee between them, so a delayed `Depleted` can arrive after a later `Replenished` and leave the read-model wrong. Preferred solution: both events on one topic, keyed by `productId` (same partition, ordered). Alternative: guard with an event timestamp and ignore stale events, which needs an extra column and has its own clock questions. Decide, document, and make sure the Inventory side adopts the same layout.
- The events say "the product is `ACTIVE`" in their trigger, but `stockStatus` is tracked for every product, active or not. The consumer should not look at `status`.
- Newly created products start `OUT_OF_STOCK`. If a stock event arrives before the product creation is committed (events racing with the create), the consumer sees an unknown product and drops it; discuss whether that is acceptable here (it is mostly theoretical because Inventory only emits after it has created a `StockItem` from `ProductCreated`).
- Consumers must tolerate unknown fields in the payload.

## Starting Point (Inventory side and Kafka setup, collected 2026-10-12)
- **Ordering question is settled:** Inventory publishes both events to **one topic, `inventory.stock-events`** (declared by Inventory, 3 partitions, 1 replica), keyed by `productId` ([Inventory task 05](../inventory-service/05-kafka-spring-setup-and-topic.md), [task 06](../inventory-service/06-stock-events-publish-and-listen.md)). Catalog only subscribes and must **not** declare it with a `NewTopic` bean; put the name in a constant/property next to `KafkaTopics.PRODUCT_EVENTS`.
- **Telling the events apart:** both payloads have the same shape (`eventId`, `productId`, `timestamp`) and carry no Java type headers. The event type is in the `eventType` record header (`ProductStockReplenished` / `ProductStockDepleted`). One listener reading a single Catalog-owned record type and branching on the header is enough; an unknown `eventType` should be logged and acknowledged, not retried.
- **Deserialization:** Catalog's consumer uses `ErrorHandlingDeserializer` → `JacksonJsonDeserializer` with `spring.json.use.type.headers: false`, and the target type is set per listener (`spring.json.value.default.type`, as in `ProductCreatedLoggingListener`). A malformed payload therefore fails in the **deserializer** and reaches the error handler as a `DeserializationException` (not retryable by `DefaultErrorHandler`). Check that the target record tolerates unknown properties.
- **Consumer group:** `application.yml` has no `spring.kafka.consumer.group-id`; set `catalog-service` (in `application.yml` or on the listener). Kubernetes runs **2 replicas**, so a fixed group is what makes each stock event apply once, with the 3 partitions split between the pods.
- **Missing topic at startup:** if Inventory has not created `inventory.stock-events` yet, the listener only logs warnings (`missing-topics-fatal` defaults to `false`) and starts consuming once the topic appears. Tests must create the topic themselves (broker auto-creation is disabled).
- **Temporary consumer to remove:** `messaging/ProductCreatedLoggingListener` (group `catalog-service.product-created-logger`).
- **Running against the real Inventory:** Compose/Kubernetes wiring for Inventory's Kafka connection is still open in [Inventory task 18](../inventory-service/18-containerization-and-deployment.md); the end-to-end check is listed there.
