---
status: DONE
service: catalog-service
---
# Transactional Outbox for `ProductCreated`

## Context
[Task 11](11-product-created-publish-and-listen.md) publishes `ProductCreated` after commit with a plain `KafkaTemplate`. If the application crashes between the commit and the publish, or the broker is down, the product exists but Inventory never learns about it, and no `StockItem` is created. Writing the database row and sending the message are two separate operations and cannot be made atomic together (dual-write problem).

The transactional outbox pattern solves this: the event is stored in an `outbox` table in **the same database transaction** as the product, and a separate relay reads the table and publishes to Kafka. Delivery becomes **at-least-once**, and consumers must therefore be idempotent.
*References:* [Catalog Spec — EP-CAT-01 step 4](../../design/services-specs/catalog-service-spec.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Inventory Spec — idempotent handler note](../../design/services-specs/inventory-service-spec.md)

## Acceptance Criteria
- [x] Flyway migration `V3__create_outbox_table.sql`: columns for event id (UUID), aggregate type and id, event type, topic, message key, JSON payload, `created_at`, and a published marker (`published_at` or a status), with an index supporting "oldest unpublished first".
- [x] `ProductService.create…` saves the `Product` and the outbox row in **one transaction**. If either fails, neither is stored.
- [x] The direct `KafkaTemplate` publishing from task 11 is removed from the product-creation path.
- [x] A relay (scheduled poller) reads unpublished rows in order, publishes them with `productId` as key, waits for the broker acknowledgment, and only then marks the row as published.
- [x] The relay is safe with several application instances (the Kubernetes Deployment runs 2 replicas): rows are claimed with `SELECT … FOR UPDATE SKIP LOCKED` or an equivalent so two relays do not publish the same row concurrently. Duplicates can still happen after a crash and are acceptable.
- [x] Each event carries a unique `eventId` (header and/or payload field) so consumers can deduplicate.
- [x] Published rows are cleaned up (deleted or archived) after a retention period; the cleanup is configurable and tested.
- [x] Tests (Testcontainers PostgreSQL + Kafka):
  - normal flow: the event reaches Kafka once after the product is created;
  - broker down during create → `201` returned, row stays unpublished; after the broker comes back, the event is published (the scenario that task 11 showed to lose events);
  - failure during the product save rolls back the outbox row too (no orphan event);
  - two relay instances do not double-publish the same row under normal conditions;
  - poison/slow rows do not block newer rows indefinitely (document the strategy: retry count, back-off, or alert).
- [x] The `ProductCreated` payload and topic are unchanged versus task 11, so consumers are unaffected.

## Technical Notes / Constraints
- **Polling relay vs. change data capture:** polling is simple and enough for this project; CDC (for example Debezium reading the Postgres WAL) avoids polling latency and load but adds a connector and infrastructure. Pick polling here and note CDC as the alternative.
- Think about ordering: the relay should publish rows of the same aggregate in creation order. With `ProductCreated` as the only event type this is trivial, but the design should not break when more event types are added later.
- The outbox row is written by the same code path inside the `@Transactional` method (not by an `AFTER_COMMIT` listener, which would re-introduce the problem).
- Keep outbox classes in their own package (`outbox`) with a small interface such as "append event in the current transaction", so other events (and, later, other services' copies) reuse it.
- A relay failure must never fail the HTTP request; it only delays delivery. Expose a metric or at least a log line for "unpublished event age", which is the signal that something is stuck.
- Idempotency on the consumer side (Inventory ignores a repeated `ProductCreated`) is already required by the Inventory spec; this task only needs to document that the guarantee now relies on it.
- **Implemented:** package `outbox`: `Outbox.append(...)` (`Propagation.MANDATORY`, so it cannot be used outside a transaction), `OutboxEvent` entity, `OutboxRelay` (polling, `@Scheduled` every `catalog.outbox.relay-interval` = 500 ms) and the cleanup job (`catalog.outbox.retention` = 7 days, every `cleanup-interval` = 1 h). Relay vs. CDC: polling chosen; Debezium on the PostgreSQL WAL noted as the alternative. The `@Async` + `AFTER_COMMIT` producer and `AsyncConfig` were removed.
- **Claiming:** `SELECT … WHERE published_at IS NULL AND next_attempt_at <= now ORDER BY created_at LIMIT n FOR UPDATE SKIP LOCKED`. The row locks are held while the batch is sent; a second replica skips them. The row is marked published only after the broker acknowledgment (`publish-timeout` = 10 s). Duplicates after a crash or a timed-out acknowledgment are possible: at-least-once, and Inventory deduplicates by `eventId` (sent in the payload and as the `eventId` header).
- **Failure strategy (decision):** a failing event gets `attempts + 1` and `next_attempt_at = now + 2^attempts s` (capped at `max-retry-delay` = 5 min). It is retried forever and never dropped. Each failure logs an error with how long the event has been unpublished (the "stuck" signal). A batch stops at its first failure (with a broker outage every further send would also wait for a timeout). Because a failing event waits, newer events overtake it; with `ProductCreated` as the only event type per product this cannot reorder anything, but a second event type per aggregate would need "hold back later events of a failing aggregate".
- **Tests:** a broker outage is simulated by making the spied `KafkaTemplate.send` fail, which is what the relay sees when the broker is unreachable. A real unreachable broker is covered by `KafkaUnavailableIntegrationTest` (row stays unpublished, request still fast). The two-relay test proves `SKIP LOCKED`: while one transaction holds the claimed rows, a second claim returns nothing.
- **Payload and topic unchanged** versus task 11 (`ProductCreatedEventIntegrationTest` still passes); the `eventId` header is new.
