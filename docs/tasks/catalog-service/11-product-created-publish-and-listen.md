---
status: DONE
service: catalog-service
---
# `ProductCreated` Event: Publish and Listen in the Same Service

## Context
This is the Kafka discipline assignment, implemented on the real domain. Creating a product (EP-CAT-01 step 4) publishes `ProductCreated`, and a listener **in the same service** receives it and logs the message key, partition and offset. Inventory Service will consume this event later; for now the in-service listener proves the broker path works end to end.

The first version publishes with a plain `KafkaTemplate` after the database commit. This has a known weakness (database write and publish can diverge), which [task 19](19-transactional-outbox.md) fixes with the transactional outbox. Observing the weakness is part of the learning goal.

**Depends on:** [Create product](07-create-product.md) (and therefore category creation), [Spring Kafka setup and topic](10-kafka-spring-setup-and-topic.md).
*References:* [Catalog Spec — EP-CAT-01, Events emitted](../../design/services-specs/catalog-service-spec.md), [Event Catalogue — `ProductCreated`](../../design/services-requirements/events-catalogue.md)

## Acceptance Criteria
- [x] The service is a Spring Boot 4.1 / Java 25 project (already the case; do not change versions).
- [x] The event contract is an **immutable Java record**, for example `ProductCreatedEvent(UUID eventId, UUID productId, Instant timestamp)`. `eventId` is mandatory (non-null, validated in the record's compact constructor) and is generated per event. `timestamp` serializes as an ISO-8601 UTC instant.
- [x] A producer component sends the event with `KafkaTemplate`, passing **`productId` as the message key**, so all events of one product go to the same partition and keep their order.
- [x] Publishing happens only after the product transaction has committed (for example `@TransactionalEventListener(phase = AFTER_COMMIT)`). A failed or rolled-back create produces no event.
- [x] A `@KafkaListener` (own consumer group) reads the topic from [task 10](10-kafka-spring-setup-and-topic.md) and logs **key, partition number and offset** (for example by receiving a `ConsumerRecord` or using the `KafkaHeaders` receive headers) plus the `eventId` and `productId`.
- [x] `POST /catalog/products` still returns `201 { productId }` when the broker is unavailable; the publish failure is logged and does not fail the request. The HTTP thread does not wait for the broker acknowledgment.
- [x] Automated test (PostgreSQL + Kafka via Testcontainers): create a category and a product through HTTP, then assert with Awaitility (no `Thread.sleep`) that exactly one event with the right `productId` and key arrives, and that the listener saw a valid partition and offset. A create that fails validation or hits a missing category produces no event.
- [x] **Manual run recorded in the PR** (the assignment's end-to-end check): start the broker container (Compose), start the catalog service, send an HTTP `POST /catalog/categories` and then `POST /catalog/products`, and copy the listener log line showing key, partition and offset. Repeat with several products to see that different keys spread across partitions.
- [x] Documentation: add `eventId` to the `ProductCreated` payload in the [Event Catalogue](../../design/services-requirements/events-catalogue.md) and the Catalog spec (it is needed for consumer deduplication later).

## Technical Notes / Constraints
- Why `AFTER_COMMIT` and not publishing inside the transaction: an event sent before commit may reach consumers when the product does not exist yet, or when the transaction later rolls back.
- Even with `AFTER_COMMIT`, a crash between commit and publish loses the event, and a failed publish is only logged. This is the dual-write problem. Do not patch it with retries here; write down the observed gap (broker stopped, product created, no event) in the PR as motivation for task 19.
- Producer settings to decide and document: `acks`, idempotent producer, retries, delivery timeout. Check what the defaults of the client in Boot 4.1 are instead of assuming.
- The listener here is a demonstration consumer. Keep it in its own class, mark it temporary, and remove it in [task 18](18-consume-stock-events.md) when the real consumers exist (Inventory will own the real `ProductCreated` consumer).
- Security is not yet in place for the endpoints used in the manual run ([task 12](12-trusted-header-security.md)), so no headers are needed.
- **Producer settings (decision):** `acks=all` and `enable.idempotence=true`, the kafka-clients 4.x defaults, stated explicitly. Retries stay at the default (effectively unlimited within `delivery.timeout.ms` = 120 s). `max.block.ms` is lowered from 60 s to 5 s, so a dead broker does not hold a publishing thread for a minute.
- **Not waiting on the HTTP thread:** `ProductEventProducer` is `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` (`@EnableAsync` in `config/AsyncConfig`). With the broker unreachable, `POST /catalog/products` returned `201` in about 100 ms.
- **Manual run (Compose, for the PR):** after `docker compose up -d --build catalog-service`, one category and six products were created. The listener logged, for example, `Received ProductCreated: key=8d3b5d57-…, partition=0, offset=0, eventId=fce65f69-…`; the six keys spread over partitions 0, 1 and 2 (offsets counted per partition).
- **Observed dual-write gap (motivation for task 19):** with the broker stopped (`docker compose stop kafka`), a create returned `201`, the product row was committed, and the producer logged `Failed to publish ProductCreated …`. After `docker compose start kafka`, the event was never delivered: 0 events for that product.
