---
status: DONE
service: inventory-service
---
# Stock Events: Publish and Listen in the Same Service

## Context
This is the Kafka discipline assignment, implemented on the real domain. When an adjustment moves `availableQuantity` across zero, Inventory publishes `ProductStockReplenished` (0 → >0) or `ProductStockDepleted` (>0 → 0). A listener **in the same service** receives the events and logs the message key, partition and offset. Catalog Service will be the real consumer later; the in-service listener proves the broker path end to end.

The first version publishes with a plain `KafkaTemplate` after the database commit. That has a known weakness (database write and publish can diverge), which [task 17](17-transactional-outbox.md) fixes with the transactional outbox.

**Depends on:** [Adjust stock quantity](04-adjust-stock-quantity.md), [Spring Kafka setup and topic](05-kafka-spring-setup-and-topic.md).
*References:* [Inventory Spec — EP-INV-02 steps 5, Emits](../../design/services-specs/inventory-service-spec.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Catalog stock-event consumers](../catalog-service/18-consume-stock-events.md)

## Acceptance Criteria
- [x] Spring Boot 4.1 / Java 25 project (already the case; do not change versions).
- [x] Event contracts are **immutable Java records**, for example `ProductStockReplenishedEvent(UUID eventId, UUID productId, Instant timestamp)` and `ProductStockDepletedEvent(...)`. `eventId` is mandatory (validated in the compact constructor) and generated per event; `timestamp` is an ISO-8601 UTC instant.
- [x] The adjust service detects crossings from `before` and `after` computed in the same locked transaction: 0 → >0 produces `Replenished`, >0 → 0 produces `Depleted`; any other change (including >0 → >0 and a no-op crossing like 0 → 0) produces nothing.
- [x] Events are sent with `KafkaTemplate` using **`productId` as the message key**, to the topic from task 05. Publishing happens only after the transaction commits (for example `@TransactionalEventListener(phase = AFTER_COMMIT)`); a rolled-back adjustment emits nothing.
- [x] Event publishing sits behind a small interface (for example `StockEventPublisher`), so the outbox in task 17 replaces the implementation without changing callers. The reserve and release tasks reuse it.
- [x] A `@KafkaListener` (own consumer group) reads the topic and logs **key, partition number and offset**, plus `eventId`, `productId` and event type.
- [x] `POST …/adjust` still returns `200` when the broker is unavailable; the publish failure is logged and does not fail the request. The HTTP thread does not wait for the broker acknowledgment.
- [x] Automated test (PostgreSQL + Kafka via Testcontainers): restock from 0 → one `Replenished`; write-off to 0 → one `Depleted`; restock 5 → 8 → no event; a rejected write-off (`422`) → no event. Use Awaitility, not `Thread.sleep`. Assert key and a valid partition/offset in the listener.
- [ ] **Manual run recorded in the PR** (the assignment's end-to-end check): start the broker container (Compose), start the inventory service, send `POST /inventory/stock/{productId}/adjust` with `{"delta": 10}` and then `{"delta": -10}`, and copy the listener log lines showing key, partition and offset. Repeat with several product IDs to see different keys spread over partitions.
- [x] Documentation: add `eventId` to the `ProductStockReplenished` / `ProductStockDepleted` payloads in the [Event Catalogue](../../design/services-requirements/events-catalogue.md) and drop the "only for `ACTIVE` products" qualifier from the Inventory spec, the catalogue and EP-INV-06/07 (Inventory does not know product status, and Catalog applies the stock status to every product).

## Technical Notes / Constraints
- Why `AFTER_COMMIT`: an event sent before the commit may reach consumers describing stock that was then rolled back.
- Even with `AFTER_COMMIT`, a crash between commit and publish loses the event, and a failed publish is only logged (dual-write problem). Do not patch it with retries here; write the observed gap (broker stopped, stock changed, no event) into the PR as motivation for task 17.
- Events can be reordered if publishing runs on different threads for the same product; sending from the after-commit callback in commit order with the same key keeps order in practice, but the guarantee is only fully given by the outbox design in task 17. Mention this.
- Producer settings to decide and document: `acks`, idempotent producer, retries, delivery timeout.
- The listener is a demonstration consumer: keep it in its own class, mark it temporary, and remove it in [task 15](15-consume-product-created.md) when the first real consumer exists.
- Security is not in place yet ([task 07](07-trusted-header-security.md)), so no headers are needed in the manual run.

## Implementation Notes / Deviations
- **Event type is a Kafka header.** `ProductStockReplenished` and `ProductStockDepleted` have the same payload (`eventId`, `productId`, `timestamp`) and share one topic, so every record carries an `eventType` header (`ProductStockReplenished` / `ProductStockDepleted`). Consumers pick the payload class from it (`StockEvents.classOf`). The payload is unchanged versus the Event Catalogue; the header is not described there yet. Catalog's [task 18](../catalog-service/18-consume-stock-events.md) must read it.
- **Crossing detection** is `StockEvents.forTransition(productId, before, after)`, reusable by reserve and release. The service calls `StockEventPublisher` inside the locked transaction; `KafkaStockEventPublisher` sends after commit (`@TransactionalEventListener(AFTER_COMMIT)`, with `fallbackExecution = true`).
- **Producer settings:** `acks: all`, idempotent producer, `max.block.ms: 1000`, `request.timeout.ms: 5000`, `delivery.timeout.ms: 10000` (see `application.yml`). The send does not wait for the acknowledgment. With the broker down, a request can be delayed by up to about `max.block.ms` while the producer waits for metadata; the failure is logged and the request still returns `200`.
  - **Correction (2026-10-12):** these settings were never committed to `application.yml`; the producer runs on the Kafka client defaults. `acks=all` and the idempotent producer are defaults anyway, but `max.block.ms` is **60 s** (`delivery.timeout.ms` 120 s, `request.timeout.ms` 30 s). With the broker down, `send()` can therefore block the HTTP request for up to about 60 s before the failure is logged (`AdjustStockBrokerDownTest` still passes, just slowly). Either add the intended values to `application.yml` or accept it until the outbox ([task 17](17-transactional-outbox.md)) takes `send()` off the request path.
- **Demo listener** (`DemoStockEventListener`, own consumer group) logs class, `eventId`, `productId`, key, partition and offset. Temporary; removed in [task 15](15-consume-product-created.md).
- **Documentation criterion:** the Event Catalogue already contains `eventId` and "regardless of its Catalog status", and the Inventory spec has no remaining `ACTIVE`-only qualifier for these events, so nothing had to change.
- **Not done:** the manual end-to-end run for the PR needs a real broker (see the [broker task](../infrastructure/kafka-broker-setup.md)). Also record in the PR the known gap this task leaves open: broker stopped, stock changed, no event is delivered (closed by [task 17](17-transactional-outbox.md)).
- *Sprint-end spec update (2026-10-12):* the `eventType` header, topic and delivery behaviour are now in the [Inventory spec](../../design/services-specs/inventory-service-spec.md) (*Stock Events*) and the [Event Catalogue](../../design/services-requirements/events-catalogue.md).
