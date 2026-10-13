---
status: DONE
service: catalog-service
---
# Group Assignment: Fault-Tolerant Messaging (Catalog → Inventory), Experiment and Report

## Context
Closes the group assignment "Fault-tolerant inter-service messaging with the Transactional Outbox": asynchronous Kafka exchange between two services, a transactional outbox in the producer, `@RetryableTopic` + DLT and an Idempotent Consumer (keys in the database) in the consumer, a DLT integration test, a consumer-failure experiment, and a report.

**Decision (team, 2026-10-12): the demonstrated pair is Catalog → Inventory (`ProductCreated`).** Catalog is the producer; its outbox already exists ([task 19](19-transactional-outbox.md)). Inventory is the consumer; its `ProductCreated` handler ([Inventory task 15](../inventory-service/15-consume-product-created.md)) is rebuilt with `@RetryableTopic` and the Idempotent Consumer. The alternative (Inventory → Catalog stock events) was not chosen: it would need Inventory's outbox ([Inventory task 17](../inventory-service/17-transactional-outbox.md)) and non-blocking retries on an ordered stream.

*References:* [Report](../../reports/messaging-fault-tolerance.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Inventory Spec — EP-INV-EVT-01](../../design/services-specs/inventory-service-spec.md)

## Acceptance Criteria

### Producer (Catalog) — already in place
- [x] `ProductCreated` is written to `outbox_events` in the same transaction as the product (one `JpaTransactionManager`); a relay publishes it with key `productId` and headers `eventType`, `eventId` (task 19).
- [x] Atomicity, broker outage and recovery, `SKIP LOCKED` with two relays: `OutboxIntegrationTest`.

### Consumer (Inventory) — `@RetryableTopic` and DLT
- [x] `ProductCreatedListener` uses `@RetryableTopic` (4 attempts: source topic + `catalog.product-events-inventory-retry-0/1/2` after 1, 2, 4 s) and a `@DltHandler` on `catalog.product-events-inventory-dlt` that logs key, partition, offset and the exception from the `kafka_exception-*` headers.
- [x] Retry topics and DLT carry the consumer's name and are created by Inventory (3 partitions, replication 1); broker auto-creation stays off.
- [x] Conversion failures (not JSON, missing `eventId` or `productId`) are fatal: straight to the DLT. Database errors go through the retry topics.
- [x] Retry forwarding uses a text `KafkaTemplate` (`defaultRetryTopicKafkaTemplate`), so the forwarded JSON is not encoded a second time.
- [x] Other Inventory listeners keep the blocking `DefaultErrorHandler` + DLT (`OrderShipped`: per-order ordering).

### Consumer (Inventory) — Idempotent Consumer
- [x] `eventId` is mandatory in Inventory's `ProductCreatedEvent`.
- [x] Flyway `V3__create_processed_events_table.sql` in Inventory: `processed_events(event_id, consumer, processed_at)`, primary key `(event_id, consumer)`.
- [x] `StockService.applyProductCreated`: `INSERT … ON CONFLICT DO NOTHING` into `processed_events` and the `StockItem` insert in one transaction; a duplicate inserts no key and skips the change. A failed attempt rolls back its key.
- [x] Keys older than `inventory.idempotency.retention` (30 days) are deleted by a scheduled job.

### Tests
- [x] DLT test (Testcontainers Kafka + PostgreSQL): non-JSON, no `productId`, no `eventId` → DLT, not via retry topics; later events still applied.
- [x] Transient failure → `-retry-0` → applied once; DLT empty.
- [x] Same `eventId` twice → business method once, one key.
- [x] Failure after commit (`FailAfterCommitExperimentTest`) → redelivered via the retry topic → skipped → one effect.

### Experiment and Report
- [x] Docker Compose run with real Catalog and Inventory (overlay `docs/reports/messaging-experiment.compose.yml`): normal flow, broker outage on the producer side, consumer failure after commit, poison message. Output recorded.
- [x] Report [`docs/reports/messaging-fault-tolerance.md`](../../reports/messaging-fault-tolerance.md): topic topology, delivery guarantees per hop, test mapping, experiment protocol, known limits.

## Technical Notes / Constraints
- **Ordering:** non-blocking retries give up per-key ordering while a record is retried. Fine for `ProductCreated` (one event per product, idempotent effect); the ordered streams (stock events in Catalog, `OrderShipped` in Inventory) keep blocking retries. Documented in the report.
- **Main topic creation:** `@RetryableTopic` with `autoCreateTopics` also creates the source topic `catalog.product-events` if it is missing, with the same 3 partitions Catalog declares; KafkaAdmin never changes an existing topic.
- **Second KafkaTemplate:** declaring the retry template switches off Spring Boot's auto-configured `kafkaTemplate`, so Inventory now declares that one explicitly (`@Primary`, same producer factory).
- **Experiment switch:** `inventory.experiments.fail-after-commit` (default `false`) exists only for the experiment.
- **Not covered here:** Inventory's own outbox for stock events ([Inventory task 17](../inventory-service/17-transactional-outbox.md)) and permanent Kafka wiring of Inventory in Compose/Kubernetes ([Inventory task 18](../inventory-service/18-containerization-and-deployment.md)).
