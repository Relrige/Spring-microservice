---
status: DONE
service: order-service
---
# Kafka Setup and Transactional Outbox for Order Events

## Context
Order publishes `OrderPlaced`, `OrderPaid`, `OrderShipped` and `OrderCancelled` to its own topic. Each status change and its event must be stored atomically, so an event is never lost and never sent for a change that was rolled back. Catalog and Inventory solved this with a transactional outbox; Order uses the same pattern. Three services (Inventory, Delivery, Customer) already consume this topic and are waiting for it.
*References:* [Order Spec — Order Events](../../design/services-specs/order-service-spec.md), [Event Catalogue — Order Lifecycle](../../design/services-requirements/events-catalogue.md), [Inventory outbox task](../inventory-service/17-transactional-outbox.md), [Catalog outbox task](../catalog-service/19-transactional-outbox.md)

## Acceptance Criteria
- [x] Add `spring-boot-starter-kafka` (+ Testcontainers Kafka); producer settings as in Inventory (`acks=all`, idempotence, JSON without type headers).
- [x] Order declares `order.order-events` (3 partitions; name from `ORDER_EVENTS_TOPIC`).
- [x] Flyway migration for `outbox_events`; `Outbox.append(event)` is called **inside** the business transaction; a relay (fixed delay, `SKIP LOCKED` batches, order per key kept) publishes with key `orderId` and headers `eventType` and `eventId`; published rows are deleted after a retention period.
- [x] Event records exactly as the catalogue: `OrderPlaced`, `OrderPaid` (fat), `OrderShipped`, `OrderCancelled`; every payload has `eventId` and `timestamp`.
- [x] Tests (Testcontainers Kafka + PostgreSQL): an appended event reaches the topic with key and headers; a rolled-back transaction publishes nothing; a broker outage delays events but loses none; events of one order keep their order.
- [x] A contract test per event checks the JSON against what the consumers require (Delivery's `OrderPaid` validation: every `deliveryDetails` field, a non-empty `items` list, `totalAmount`, `currency`).

## Technical Notes / Constraints
- Copy the outbox from Inventory rather than inventing a new one; delivery is at-least-once and the consumers are idempotent.
- `OrderPaid` carries the recipient's personal data; do not log payloads.

## Implementation
Done on 2026-10-16:
- `spring-boot-starter-kafka` (+ `spring-kafka-test`, `testcontainers-kafka`); producer settings copied from Inventory (`acks=all`, `enable.idempotence`, `max.block.ms=5000`, `JacksonJsonSerializer` without type headers). `config/KafkaConfig` declares `order.order-events` (3 partitions, 1 replica, name from `ORDER_EVENTS_TOPIC`) and the typed `KafkaTemplate<String, Object>`; `config/SchedulingConfig` enables the relay's schedules.
- `V2__create_outbox_events_table.sql` and `outbox/OutboxEvent`, `OutboxProperties` (`order.outbox.*`), `OutboxRepository`, `OutboxRelay` copied from Inventory with the aggregate being the order instead of the product: one relay at a time across replicas (advisory lock), events sent in `sequence_number` order and each acknowledged before the next, a failing event and the later events **of the same order** held back with exponential back-off while other orders' events keep flowing, published rows deleted after 7 days, gauge `order.outbox.oldest.unpublished.age`.
- **No `SKIP LOCKED` batches** (the criterion mentions them): as in Inventory, parallel relays could reorder the events of one order, so a single relay holds an advisory lock instead. Ordering per order matters here: Delivery creates the shipment from `OrderPaid` and hands it over on `OrderShipped`.
- `outbox/Outbox.append(event)` with `Propagation.MANDATORY`: it refuses to run outside the business transaction. Key = orderId, headers `eventType` and `eventId`, payload serialized when appended.
- `events/`: `OrderEvent` (sealed) and `OrderPlacedEvent`, `OrderPaidEvent` (fat, with `deliveryDetails` and `items`), `OrderShippedEvent`, `OrderCancelledEvent` (reason `CUSTOMER_CANCELLED` / `EXPIRED`), each with a factory from `OrderEntity`. `eventType()` is the header, not a payload field.
- Configuration: `order.kafka.topics.order-events`, `order.outbox.*`; `SPRING_KAFKA_BOOTSTRAP_SERVERS` and `ORDER_EVENTS_TOPIC` in `.env.example`, `.env` and `k8s/order/app-config.yaml`.
- Tests: `OrderEventsJsonTest` (5: the exact fields of each event, `OrderPaid` with everything Delivery validates and a non-null `customerId`, no `eventType` in the payload) and `OutboxIntegrationTest` (7 on PostgreSQL 18 and Kafka 4.1: the topic has 3 partitions; a committed event arrives with key, both headers and the stored payload, without Java type headers; a rolled-back transaction leaves nothing; appending outside a transaction is refused; three events of one order arrive in order; a broker outage holds back only the failing order and loses nothing; cleanup deletes only rows past the retention). `OrderServiceApplicationTests` and `OrderAccessIntegrationTest` now also start the Kafka container.

## Not done here
- No business code appends events yet; the endpoint tasks (08, 09, 11, 13, 14) call `outbox.append(...)` in their transactions.
