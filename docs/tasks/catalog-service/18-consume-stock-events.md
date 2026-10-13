---
status: DONE
service: catalog-service
---
# EP-CAT-EVT-01 / EP-CAT-EVT-02: Consume Stock Events

## Context
Inventory Service tells Catalog when a product's available quantity crosses zero. Catalog applies that to the `Product.stockStatus` read-model so product listings never need to call Inventory. Both consumers follow the same pattern and are built together here.

Inventory Service may not exist yet. Tests publish the events themselves, using the payload from the catalogue.
*References:* [Catalog Spec — EP-CAT-EVT-01, EP-CAT-EVT-02](../../design/services-specs/catalog-service-spec.md), [Event Catalogue — `ProductStockReplenished`, `ProductStockDepleted`](../../design/services-requirements/events-catalogue.md), [Kafka setup](10-kafka-spring-setup-and-topic.md)

## Acceptance Criteria
- [x] `@KafkaListener` consumers for `ProductStockReplenished` (→ `stockStatus = IN_STOCK`) and `ProductStockDepleted` (→ `stockStatus = OUT_OF_STOCK`), payload `{ productId, timestamp }`, in a dedicated consumer group (`catalog-service`).
- [x] Unknown `productId` → log a warning and acknowledge (the message is not retried and does not block the partition).
- [x] Re-delivery of the same event, or an event matching the current value, is an idempotent no-op.
- [x] Malformed or undeserializable payloads do not block the consumer: they are sent to a dead-letter topic (or skipped and logged, if the team prefers) after a bounded number of attempts. The choice is documented.
- [x] Transient failures (for example the database being briefly unavailable) are retried with a bounded back-off before going to the dead-letter handling.
- [x] Offsets are committed only after the database update succeeded (default container ack mode is acceptable, but state which mode is used and why).
- [x] The stock status update changes only the `stockStatus` column and does not overwrite concurrent manager edits (see the optimistic-locking decision in [task 03](03-product-entity-and-persistence.md)).
- [x] The temporary consumer from [task 11](11-product-created-publish-and-listen.md) is removed.
- [x] Tests (Testcontainers Kafka + PostgreSQL): replenished then depleted updates the product; unknown product is acknowledged; duplicate event is a no-op; a poison message does not stop later messages from being processed.

## Technical Notes / Constraints
- **Ordering question to settle with the Inventory spec:** if `Replenished` and `Depleted` are on different topics, Kafka gives no ordering guarantee between them, so a delayed `Depleted` can arrive after a later `Replenished` and leave the read-model wrong. Preferred solution: both events on one topic, keyed by `productId` (same partition, ordered). Alternative: guard with an event timestamp and ignore stale events, which needs an extra column and has its own clock questions. Decide, document, and make sure the Inventory side adopts the same layout.
- The events say "the product is `ACTIVE`" in their trigger, but `stockStatus` is tracked for every product, active or not. The consumer should not look at `status`.
- Newly created products start `OUT_OF_STOCK`. If a stock event arrives before the product creation is committed (events racing with the create), the consumer sees an unknown product and drops it; discuss whether that is acceptable here (it is mostly theoretical because Inventory only emits after it has created a `StockItem` from `ProductCreated`).
- Consumers must tolerate unknown fields in the payload.
- **Topic layout (decision):** one topic, `inventory.stock-events` (3 partitions), for both event types; key `productId`; the type is in the `eventType` header. Ordering between `Replenished` and `Depleted` of one product is therefore guaranteed, and no timestamp guard is needed. Recorded in the Event Catalogue and the Inventory spec. Catalog also declares the topic (`KafkaConfig`) until Inventory exists; identical declarations are harmless.
- **Poison messages (decision):** dead-letter topic `catalog.stock-events-dlt` (owned by Catalog, 1 partition) through `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`. Undeserializable payloads (including a missing `productId`) are not retried; other failures are retried 4 times with exponential back-off (1, 2, 4, 8 s) and then dead-lettered. The raw bytes of an undeserializable message are republished unchanged.
- **Ack mode:** the container default `BATCH`: offsets are committed after the listener has returned for the records of a poll, i.e. after the database update committed. Re-delivery after a crash is harmless because setting a value is idempotent and a partition is replayed in order.
- **Update:** a single `UPDATE products SET stock_status = … WHERE id = …` (no read-modify-write), so it can neither overwrite nor be overwritten by a concurrent manager edit (`@DynamicUpdate`, no `@Version`, as decided in task 03). Zero updated rows = unknown product → warning, acknowledged. An unknown `eventType` on the topic is logged and skipped.
- **Race with product creation (accepted):** a stock event for a product that is not committed yet is dropped as unknown. Inventory only emits stock events after it consumed `ProductCreated`, which Catalog publishes after the commit, so this cannot happen in practice.
- The temporary `ProductCreatedLoggingListener` was removed; task 11's integration test now checks key, partition and offset from the topic itself.
