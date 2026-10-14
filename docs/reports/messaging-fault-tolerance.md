# Fault-Tolerant Inter-Service Messaging: Topic Topology and Test Results

Group assignment: *fault-tolerant inter-service messaging with the Transactional Outbox*. Demonstrated pair: **Catalog Service → Inventory Service** (`ProductCreated`). Date of the runs: 2026-10-12.

## 1. What Is Demonstrated

| Requirement | Where | Evidence |
|---|---|---|
| Asynchronous Kafka exchange between two independent services | Catalog publishes `ProductCreated`, Inventory consumes it and creates an empty `StockItem` | §4 phase A |
| Transactional Outbox in the producer: entity and event in one database transaction | Catalog: `ProductService.createProduct` → `Outbox.append` (`Propagation.MANDATORY`), relay `OutboxRelay` | `OutboxIntegrationTest`, §4 phase B |
| Retries and error isolation in the consumer with `@RetryableTopic` and a DLT | Inventory: `ProductCreatedListener` (`@RetryableTopic` + `@DltHandler`) | `ConsumedEventsIntegrationTest`, §4 phase D |
| Idempotent Consumer with keys stored in the database | Inventory: table `processed_events`, `StockService.applyProductCreated` | `ConsumedEventsIntegrationTest`, `FailAfterCommitExperimentTest`, §4 phase C |
| Integration test: an unprocessable event reaches the DLT | Inventory `ConsumedEventsIntegrationTest.poisonMessages_goToTheDeadLetterTopicAndDoNotBlockLaterMessages` (Testcontainers Kafka + PostgreSQL) | §3 |
| Experiment with an artificial consumer failure, no duplicated business effect | Inventory `FailAfterCommitExperimentTest` + manual Compose run | §4 phase C |

## 2. Topic Topology

```
 Catalog Service                         Kafka (1 broker, KRaft)                          Inventory Service
 ───────────────                         ───────────────────────                          ─────────────────
 POST /catalog/products                                                                    group inventory-service
   │ one DB transaction                                                                    ProductCreatedListener
   ├─ INSERT products                                                                        │ one DB transaction
   └─ INSERT outbox_events ──► OutboxRelay ──► catalog.product-events (3) ───────────────────►├─ INSERT processed_events (key)
                              (poll 500 ms,    key = productId                   │ failure    └─ INSERT stock_items ON CONFLICT DO NOTHING
                               SKIP LOCKED)    header eventType, eventId         ▼
                                                              catalog.product-events-inventory-retry-0 (3)  after 1 s
                                                              catalog.product-events-inventory-retry-1 (3)  after 2 s
                                                              catalog.product-events-inventory-retry-2 (3)  after 4 s
                                                              catalog.product-events-inventory-dlt (3) ─────► @DltHandler (log)
```

| Topic | Owner (declares it) | Partitions | Key | Headers | Producer | Consumer group(s) |
|---|---|---|---|---|---|---|
| `catalog.product-events` | Catalog (`KafkaConfig`); also created by Inventory's `@RetryableTopic` if missing, with the same settings | 3 | `productId` | `eventType=ProductCreated`, `eventId` | Catalog `OutboxRelay` | `inventory-service` |
| `catalog.product-events-inventory-retry-0/1/2` | Inventory (`@RetryableTopic`) | 3 | `productId` | original + `kafka_*` retry headers | Inventory (retry forwarding) | `inventory-service` (one container per retry topic) |
| `catalog.product-events-inventory-dlt` | Inventory (`@RetryableTopic`) | 3 | `productId` | original + `kafka_exception-*` | Inventory | `inventory-service` (`@DltHandler`) |
| `inventory.stock-events` | Inventory | 3 | `productId` | `eventType` | Inventory | `catalog-service` (Catalog task 18) |
| `catalog.stock-events-dlt` | Catalog | 1 | `productId` | `kafka_dlt-*` | Catalog (blocking error handler) | — (manual inspection) |
| `inventory.order.order-events.dlt` | Inventory | 1 | `orderId` | `kafka_dlt-*` | Inventory (blocking error handler) | — |

Naming: `<owning service>.<aggregate>-events` for event topics; retry topics and DLTs carry the **consumer's** name (`-inventory-retry-N`, `-inventory-dlt`), because each consumer group retries and dead-letters independently and owns those topics. The broker does not auto-create topics; every topic is declared by its owner.

## 3. Delivery Guarantees per Hop

| Hop | Guarantee | Why |
|---|---|---|
| Catalog DB → outbox | atomic | product row and outbox row in one transaction (one `DataSource`, one `JpaTransactionManager`); a failed save leaves neither (`OutboxIntegrationTest.failureWhileSavingTheProductEvent_rollsBackTheProduct`) |
| outbox → Kafka | at-least-once | a row is marked published only after the broker acknowledgment; a crash or a timed-out acknowledgment re-sends it. Two relay replicas never claim the same row (`FOR UPDATE SKIP LOCKED`) |
| Kafka → Inventory | at-least-once | offsets are committed after the listener returned, i.e. after the database commit |
| Inventory effect | **effectively once** | the event key (`eventId`) is inserted into `processed_events` in the same transaction as the business change; a second delivery inserts nothing and skips the change. A failed attempt rolls back its key too, so a retry is not mistaken for a duplicate |

**Non-blocking retries and ordering.** `@RetryableTopic` moves a failed record to a retry topic and continues with the next records of the partition, so per-key ordering is lost during a retry. This is harmless for `ProductCreated`: there is one event per product and creation is idempotent. It would not be harmless for ordered streams such as the stock events (a delayed `Replenished` overtaking a later `Depleted`); that is why Catalog's stock-event consumer keeps blocking retries (`DefaultErrorHandler`), and why Inventory's `OrderShipped` consumer does too.

**Error classification.** Records that can never succeed (not JSON, missing `eventId` or `productId`) fail in the message converter with a `ConversionException`, which `@RetryableTopic` treats as fatal: straight to the DLT, no retry topics. Everything else (e.g. a database error) is retried 3 times (1 s, 2 s, 4 s) on the retry topics, then dead-lettered.

## 4. Automated Tests

Inventory (Testcontainers `apache/kafka:4.1.0` + `postgres:18-alpine`), all green (296 tests in the module):

| Test | Proves |
|---|---|
| `ConsumedEventsIntegrationTest.productCreated_createsEmptyStockItem` | normal consumption, tolerant reader |
| `…sameEventDeliveredTwice_isAppliedOnce` | Idempotent Consumer: same `eventId` twice → business method once, one key |
| `…productCreated_duplicateAndExistingStockItem_changeNothing` | different events for an existing StockItem change nothing |
| `…poisonMessages_goToTheDeadLetterTopicAndDoNotBlockLaterMessages` | non-JSON, no `productId`, no `eventId` → **DLT** in order, not via retry topics; a later valid event is still applied |
| `…transientDatabaseFailure_isRetried` | a database failure goes through `-retry-0` and is then applied; the failed attempt's key was rolled back; nothing in the DLT |
| `…productEventsTopic_otherEventTypesAreSkipped` | only `eventType=ProductCreated` is consumed |
| `…processedEventKeys_areDeletedAfterTheRetention` | key retention (30 days) |
| `FailAfterCommitExperimentTest` | failure **after** the database commit → redelivery through the retry topic → duplicate skipped → one `StockItem`, one key, `insertIfAbsent` called once |
| `MissingSourceTopicsStartupTest` | the service starts and is ready before Catalog/Order created their topics |

Catalog (green, 258 tests in the module): `OutboxIntegrationTest` (atomicity, broker outage and recovery, `SKIP LOCKED`, poison rows do not block, cleanup), `ProductCreatedEventIntegrationTest` (wire format, headers, key, partitions).

## 5. Experiment (Docker Compose, real Catalog → Kafka → Inventory)

Setup: `docker compose -f docker-compose.yml -f docs/reports/messaging-experiment.compose.yml up -d --build kafka catalog-service inventory-service`. HTTP calls were made from inside the Compose network with the gateway headers set by hand (`X-User-Role: CATALOG_MANAGER`). Logs shortened. Log timestamps come from the Docker Desktop VM clock, which ran 3 h ahead of real UTC during the run; only the differences between them matter.

**Phase A: normal flow.** Create a product in Catalog.
```
product=75b3e211-a6fb-4f99-98ec-b7505b35a3ee
inventory: StockItem created for new product 75b3e211-… (eventId=944d0979-…, topic=catalog.product-events)
stock_items rows: 1
```
(Inventory's new consumer group also caught up on 13 older `ProductCreated` events already on the topic.)

**Phase B: broker down while the producer works (dual-write gap closed).** `docker compose stop kafka`, create a product, `docker compose start kafka`.
```
product=aed403d9-…  (HTTP 201 while the broker was down)
catalog outbox row: ProductCreated published=false attempts=1
catalog: Outbox event 944edb8c-… failed to publish: attempt 1, unpublished for PT5.88S, next try in PT1S
after the broker came back: published=true, attempts=2
inventory: StockItem created for new product aed403d9-… (eventId=944edb8c-…)
inventory stock_items rows for the product: 1
```
With the task-11 design (publish after commit) the same outage lost the event; with the outbox it is delivered once the broker is back.

**Phase C: consumer fails after its commit (no duplicated business effect).** Inventory restarted with `FAIL_AFTER_COMMIT=true` (`inventory.experiments.fail-after-commit`), which throws after the database transaction of every first delivery, like a crash before the offset commit.
```
18:53:44.930 StockItem created for new product 3eae5bc4-… (eventId=ca4df546-…, topic=catalog.product-events)
18:53:44.931 EXPERIMENT: simulated consumer failure after commit, eventId=ca4df546-…; the record will be redelivered
18:53:46.095 Duplicate ProductCreated eventId=ca4df546-… skipped (topic=catalog.product-events-inventory-retry-0)
inventory stock_items rows for the product: 1
inventory processed_events rows for eventId ca4df546-…: 1
```
The event was delivered twice (source topic, then `-retry-0` about 1 s later); the business effect happened once.

**Phase D: poison message.** A non-JSON value with `eventType=ProductCreated` sent to `catalog.product-events` with `kafka-console-producer`.
```
DLT catalog.product-events-inventory-dlt: <key>  not json at all
retry-0 has it: 0  (not retried: conversion errors are fatal)
inventory: ProductCreated dead-lettered: key=… topic=catalog.product-events-inventory-dlt partition=0 offset=1
           cause=org.springframework.kafka.support.converter.ConversionException: Listener failed; Failed to convert from JSON
```
Later events were processed normally.

## 6. Known Limits and Open Points

- **DLT re-processing** is manual: inspect the record and the `kafka_exception-*` headers, fix the cause, re-publish to `catalog.product-events`. The re-published event keeps its `eventId`, so the Idempotent Consumer still prevents a double effect.
- **Polling relay vs. CDC:** the outbox is polled every 500 ms; change data capture (Debezium on the PostgreSQL WAL) would remove the polling at the cost of extra infrastructure.
- **Inventory's own stock events** are still published after commit (no outbox yet: Inventory task 17), and Catalog's stock-event consumer deduplicates by state rather than by key. The same patterns apply when that direction is hardened.
- **Experiment switch:** `inventory.experiments.fail-after-commit` exists only for this experiment and defaults to `false`; it is never set in Compose or Kubernetes manifests (only in the overlay above).
- **Inventory in Compose/Kubernetes** is not wired to Kafka permanently yet (Inventory task 18); the overlay does it for the experiment. The overlay also uses a fresh database volume, because an old local volume may still hold the pre-Flyway draft schema.
  *Update 2026-10-13:* the wiring has since been done in Compose and Kubernetes ([infrastructure task](../tasks/infrastructure/kafka-inventory-wiring-and-minikube-check.md), runbooks in [`e2e/manual`](../../e2e/manual/README.md)); the overlay is still needed for the experiment switch and the fresh volume.
