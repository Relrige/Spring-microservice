---
status: TODO
service: catalog-service
---
# Fault-Tolerant Stock-Event Consumer: `@RetryableTopic`, Idempotent Consumer, Failure Experiment and Report

## Context
This task closes the group assignment "Fault-tolerant inter-service messaging with the Transactional Outbox". Status after tasks 10–22:

| Assignment requirement | Status |
|---|---|
| Asynchronous Kafka exchange between two team services | Done: Inventory → Catalog (`inventory.stock-events`). Catalog → Inventory (`ProductCreated`) is not consumed yet ([Inventory task 15](../inventory-service/15-consume-product-created.md)). |
| Transactional Outbox in the producer (one `TransactionManager` for entity and event) | Done in Catalog for `ProductCreated` ([task 19](19-transactional-outbox.md)). **Missing in Inventory** for the stock events ([Inventory task 17](../inventory-service/17-transactional-outbox.md)). |
| Consumer retries and error isolation with `@RetryableTopic` and a DLT | Partly: [task 18](18-consume-stock-events.md) uses blocking retries (`DefaultErrorHandler` + `DeadLetterPublishingRecoverer`), not `@RetryableTopic`. |
| Idempotent Consumer with keys stored in the database | Missing: the stock update is idempotent by nature, but processed event IDs are not recorded. |
| Integration test proving an unprocessable event reaches the DLT | Exists for the blocking handler; must be redone for `@RetryableTopic`. |
| Experiment with an artificial consumer failure, no duplicated business effects | Missing. |
| Report on topic topology and fault-tolerance results | Missing. |

This task covers the **consumer side in Catalog**, the **experiment** and the **report**. For the demonstrated service pair (Inventory → Catalog) to also have an outbox on the producer side, [Inventory task 17](../inventory-service/17-transactional-outbox.md) must be done as well (see the first decision below).

*References:* [Task 18 — stock-event consumer](18-consume-stock-events.md), [Task 19 — transactional outbox](19-transactional-outbox.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Inventory Spec — Stock Events](../../design/services-specs/inventory-service-spec.md)

## Decisions to Make First (Team)
1. **Which pair is presented as the end-to-end demonstration?**
   - (a) **Inventory → Catalog** (stock events): the Catalog consumer exists and is rebuilt here; needs Inventory task 17 (outbox) for the producer side. *Suggested:* the consumer side is the larger part of the assignment, and most of it already exists in Catalog.
   - (b) **Catalog → Inventory** (`ProductCreated`): the Catalog outbox is done; needs Inventory task 15 built with the same consumer pattern (`@RetryableTopic`, DLT, idempotent consumer).
   Record the choice in the report. Both directions should eventually follow the same conventions.
2. **Ordering vs. non-blocking retries.** `@RetryableTopic` moves a failing record to a retry topic and continues with the next records, so **per-product ordering is lost** while a record is being retried. Example: `Replenished` fails, `Depleted` for the same product is applied, then the retried `Replenished` arrives and leaves the read-model at `IN_STOCK` although stock is 0. Choose and document a guard. *Suggested:* store the event `timestamp` of the last applied stock event per product (`stock_status_changed_at`) and ignore older events. Alternative: keep blocking retries for this topic and argue why. That still fails the assignment's explicit `@RetryableTopic` requirement, so it needs the teacher's agreement.

## Acceptance Criteria

### Consumer with `@RetryableTopic` and DLT
- [ ] `StockEventListener` uses `@RetryableTopic` (Spring Kafka 4.1): bounded attempts (e.g. 4) with exponential back-off, retry topics created by the framework, and a `@DltHandler` that logs key, partition, offset, `eventId` and the failure cause.
- [ ] Topic naming is explicit and documented (retry topic and DLT suffixes, e.g. `inventory.stock-events-catalog-retry-0…`, `…-catalog-dlt`), because several consumer groups may retry the same source topic. The DLT name from task 18 (`catalog.stock-events-dlt`) is either kept via configuration or replaced, and the change is reflected in the Event Catalogue.
- [ ] Non-retryable errors go straight to the DLT without retry topics: deserialization failures (`ErrorHandlingDeserializer`), a payload without `productId` or `eventId`, an unknown `eventType` is still just skipped (not an error).
- [ ] Transient errors (e.g. a database exception) go through the retry topics and are applied once the cause disappears.
- [ ] The blocking `DefaultErrorHandler` bean from task 18 is removed or limited, so the two mechanisms do not both act on the same listener. State which one applies where.
- [ ] Unknown `productId` is still logged and acknowledged (not retried, not dead-lettered).

### Idempotent Consumer
- [ ] `StockEvent` contains a mandatory `eventId` (Inventory already sends it; update the Event Catalogue example if needed).
- [ ] Flyway migration `V4__create_processed_events_table.sql`: `processed_events(event_id UUID, consumer VARCHAR, processed_at TIMESTAMPTZ, PRIMARY KEY (event_id, consumer))`.
- [ ] Recording the event ID and applying the stock change happen in **one database transaction**. A second delivery of the same `eventId` is detected (insert conflicts, e.g. `INSERT … ON CONFLICT DO NOTHING` returning 0 rows) and skipped with an `INFO`/`DEBUG` log line, without changing data.
- [ ] The ordering guard from decision 2 is applied in the same transaction.
- [ ] Old `processed_events` rows are deleted after a retention period that is longer than the longest possible redelivery window (retry topics + outbox retries). Configurable and tested, similar to the outbox cleanup.

### Tests (Testcontainers Kafka + PostgreSQL)
- [ ] An undeserializable message and a message without `eventId` end up in the DLT; later messages for the same product are still applied.
- [ ] A transient failure (repository spy throwing once) goes through a retry topic and is then applied exactly once.
- [ ] The same event delivered twice (same `eventId`) changes data once; `processed_events` has one row.
- [ ] Out-of-order delivery (older `Replenished` after newer `Depleted`) does not overwrite the newer state.
- [ ] The DLT test from task 18 is replaced, not duplicated.

### Failure Experiment (manual, recorded in the report)
- [ ] Inject a consumer failure **after the database commit but before the offset commit** (e.g. a test-only property that throws once after the transaction, or killing the container at that point). The broker redelivers the event, and the idempotent consumer skips it. Show the log lines and the `processed_events` count.
- [ ] Repeat with the producer side: stop Kafka, change stock / create a product, start Kafka again. The outbox delivers the event late, exactly one business effect is observed.
- [ ] Optional: run two Catalog instances (two replicas or two local processes) to show the consumer group sharing partitions and no double processing after a rebalance.

### Report
- [ ] `docs/reports/messaging-fault-tolerance.md` (English) with:
  - topic topology: every topic with owner, partitions, key, headers, producers, consumer groups, retry topics and DLTs (a table and a small diagram);
  - delivery guarantees per hop (outbox: at-least-once; consumer: at-least-once + idempotency = effectively once), and where duplicates and reordering can still appear;
  - test results (which tests prove which requirement) and the experiment protocol with observed output;
  - known limits and open points (e.g. CDC instead of polling, DLT re-processing procedure).

## Technical Notes / Constraints
- Check the `@RetryableTopic` API and defaults in the Spring Kafka version managed by Spring Boot 4.1 (attribute names, auto-created topic settings, `autoCreateTopics`, `numPartitions`, `replicationFactor = 1` for the single broker, and that broker auto-creation is off: retry topics must be created by the framework's admin, not the broker).
- `@RetryableTopic` retries are asynchronous: integration tests must wait with Awaitility, never `Thread.sleep`.
- The idempotency check and the business update must use the same `DataSource`/`TransactionManager`; do not use Kafka transactions for this.
- DLT messages need a documented manual procedure (inspect, fix, re-publish to the source topic). The re-published event keeps its `eventId`, so the idempotent consumer still protects against double application.
- Keep `INTERNAL_TOKEN` and other secrets out of logs, including DLT handler logs and headers.
