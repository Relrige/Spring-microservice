---
status: TODO
service: inventory-service
---
# Stock Events: Publish and Listen in the Same Service

## Context
This is the Kafka discipline assignment, implemented on the real domain. When an adjustment moves `availableQuantity` across zero, Inventory publishes `ProductStockReplenished` (0 → >0) or `ProductStockDepleted` (>0 → 0). A listener **in the same service** receives the events and logs the message key, partition and offset. Catalog Service will be the real consumer later; the in-service listener proves the broker path end to end.

The first version publishes with a plain `KafkaTemplate` after the database commit. That has a known weakness (database write and publish can diverge), which [task 17](17-transactional-outbox.md) fixes with the transactional outbox.

**Depends on:** [Adjust stock quantity](04-adjust-stock-quantity.md), [Spring Kafka setup and topic](05-kafka-spring-setup-and-topic.md).
*References:* [Inventory Spec — EP-INV-02 steps 5, Emits](../../design/services-specs/inventory-service-spec.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Catalog stock-event consumers](../catalog-service/18-consume-stock-events.md)

## Acceptance Criteria
- [ ] Spring Boot 4.1 / Java 25 project (already the case; do not change versions).
- [ ] Event contracts are **immutable Java records**, for example `ProductStockReplenishedEvent(UUID eventId, UUID productId, Instant timestamp)` and `ProductStockDepletedEvent(...)`. `eventId` is mandatory (validated in the compact constructor) and generated per event; `timestamp` is an ISO-8601 UTC instant.
- [ ] The adjust service detects crossings from `before` and `after` computed in the same locked transaction: 0 → >0 produces `Replenished`, >0 → 0 produces `Depleted`; any other change (including >0 → >0 and a no-op crossing like 0 → 0) produces nothing.
- [ ] Events are sent with `KafkaTemplate` using **`productId` as the message key**, to the topic from task 05. Publishing happens only after the transaction commits (for example `@TransactionalEventListener(phase = AFTER_COMMIT)`); a rolled-back adjustment emits nothing.
- [ ] Event publishing sits behind a small interface (for example `StockEventPublisher`), so the outbox in task 17 replaces the implementation without changing callers. The reserve and release tasks reuse it.
- [ ] A `@KafkaListener` (own consumer group) reads the topic and logs **key, partition number and offset**, plus `eventId`, `productId` and event type.
- [ ] `POST …/adjust` still returns `200` when the broker is unavailable; the publish failure is logged and does not fail the request. The HTTP thread does not wait for the broker acknowledgment.
- [ ] Automated test (PostgreSQL + Kafka via Testcontainers): restock from 0 → one `Replenished`; write-off to 0 → one `Depleted`; restock 5 → 8 → no event; a rejected write-off (`422`) → no event. Use Awaitility, not `Thread.sleep`. Assert key and a valid partition/offset in the listener.
- [ ] **Manual run recorded in the PR** (the assignment's end-to-end check): start the broker container (Compose), start the inventory service, send `POST /inventory/stock/{productId}/adjust` with `{"delta": 10}` and then `{"delta": -10}`, and copy the listener log lines showing key, partition and offset. Repeat with several product IDs to see different keys spread over partitions.
- [ ] Documentation: add `eventId` to the `ProductStockReplenished` / `ProductStockDepleted` payloads in the [Event Catalogue](../../design/services-requirements/events-catalogue.md) and drop the "only for `ACTIVE` products" qualifier from the Inventory spec, the catalogue and EP-INV-06/07 (Inventory does not know product status, and Catalog applies the stock status to every product).

## Technical Notes / Constraints
- Why `AFTER_COMMIT`: an event sent before the commit may reach consumers describing stock that was then rolled back.
- Even with `AFTER_COMMIT`, a crash between commit and publish loses the event, and a failed publish is only logged (dual-write problem). Do not patch it with retries here; write the observed gap (broker stopped, stock changed, no event) into the PR as motivation for task 17.
- Events can be reordered if publishing runs on different threads for the same product; sending from the after-commit callback in commit order with the same key keeps order in practice, but the guarantee is only fully given by the outbox design in task 17. Mention this.
- Producer settings to decide and document: `acks`, idempotent producer, retries, delivery timeout.
- The listener is a demonstration consumer: keep it in its own class, mark it temporary, and remove it in [task 15](15-consume-product-created.md) when the first real consumer exists.
- Security is not in place yet ([task 07](07-trusted-header-security.md)), so no headers are needed in the manual run.
