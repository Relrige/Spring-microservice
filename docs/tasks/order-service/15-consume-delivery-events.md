---
status: DONE
service: order-service
---
# EP-ORD-EVT-01 and EP-ORD-EVT-02: Consume `TTNGenerated` and `OrderDelivered`

## Context
Delivery reports two milestones of a shipment on its topic: the TTN was registered (`PAID → PENDING_SHIPPING`) and the parcel was delivered (`SHIPPED → DELIVERED`). Both arrive on one topic, told apart by the `eventType` header, and can be redelivered. Order applies them only from the expected status, so a duplicate or a late event changes nothing.
*References:* [Order Spec — EP-ORD-EVT-01/02](../../design/services-specs/order-service-spec.md), [Event Catalogue — Delivery Updates](../../design/services-requirements/events-catalogue.md), [Delivery consumer task (the same pattern)](../delivery-service/10-consume-order-paid.md)

## Acceptance Criteria
- [x] **One** `@KafkaListener` on `DELIVERY_EVENTS_TOPIC` (default `delivery.shipment-events`), group `order-service`, dispatching on `eventType`; other types are acknowledged and skipped.
- [x] `TTNGenerated`: `PAID` → `PENDING_SHIPPING` (check-and-set); any other status → log and acknowledge (duplicate, worker already shipped, cancelled meanwhile).
- [x] `OrderDelivered`: `SHIPPED` → `DELIVERED`; `DELIVERED` → duplicate; any other status → warning and acknowledge.
- [x] Unknown `orderId` → warning and acknowledge.
- [x] Unreadable payload → straight to `order.delivery.shipment-events.dlt` (declared by Order, 1 partition); other failures (an optimistic-lock conflict, the database down) → 4 retries with back-off, then the DLT. The missing topic is not fatal at startup.
- [x] Tests (Testcontainers Kafka + PostgreSQL): each transition; each event twice; `TTNGenerated` after a cancellation leaves the order cancelled; a poison message goes to the DLT and a later record is processed.

## Technical Notes / Constraints
- Idempotency is by state, so no processed-events table is needed (as in Delivery and Customer); explain why in the task notes.
- Offsets are committed after the database transaction (ack mode `batch`).

## Implementation
Done on 2026-10-17:
- `events/DeliveryEventsListener`: **one** `@KafkaListener` on `order.kafka.topics.delivery-events` (`DELIVERY_EVENTS_TOPIC`, default `delivery.shipment-events`), group `order-service`, reading the raw record and dispatching on the `eventType` header to `services/ShipmentProgressService`; other types are skipped. The payload is read into `events/ShipmentMilestoneEvent { eventId, orderId, ttn, timestamp }` (both events have this shape; unknown fields ignored) and validated; not JSON or a missing field → `PoisonMessageException`.
- `services/ShipmentProgressService` (`@Transactional` per event): `TTNGenerated` moves only `PAID → PENDING_SHIPPING`; `OrderDelivered` moves only `SHIPPED → DELIVERED`, logs a duplicate for `DELIVERED` and a warning for any other status; an unknown order is a warning. Anything not moved is acknowledged. A concurrent change of the same order fails on the version at commit and is retried by the error handler, and the retry sees the new status.
- `config/KafkaConfig` (copied from Delivery): `order.delivery.shipment-events.dlt` (1 partition, declared by Order), a second `KafkaTemplate` that forwards the consumed JSON text unchanged to the DLT (the outbox template is now `@Primary`), and a `DefaultErrorHandler` with 4 retries (1 s, 2 s, 4 s, 8 s) and no retry for `PoisonMessageException`. `KafkaTopics.deadLetterTopicOf` names the DLT. `application.yml`: consumer group, `auto-offset-reset: earliest`, string deserializers, `ack-mode: batch`, `missing-topics-fatal: false` (Order starts before Delivery has declared its topic).
- `DELIVERY_EVENTS_TOPIC` in `.env.example`, `.env` and `k8s/order/app-config.yaml`.
- Tests: `ShipmentProgressServiceTest` (19 with parameters: each milestone from its own status and from every other status, unknown order), `integration/DeliveryEventsIntegrationTest` (5 on Kafka 4.1 and PostgreSQL 18, records produced as Delivery produces them: `TTNGenerated` twice → `PENDING_SHIPPING`; `OrderDelivered` twice → `DELIVERED`; `TTNGenerated` after a cancellation → still `CANCELLATION_REQUESTED`; another event type and an unknown order skipped; a non-JSON record and one without `ttn` go to the one-partition DLT as the same text, and the good record behind them on the same partition is processed). "Processed" is checked by waiting until the consumer group has committed the end offset of every partition, so a duplicate that changes nothing is still known to be handled.

## Why no processed-events table
Each event moves the order **from one specific status** and is ignored in every other. A redelivered `TTNGenerated` finds `PENDING_SHIPPING` (or later) and does nothing; a redelivered `OrderDelivered` finds `DELIVERED`. The order's status already records whether the event was applied, so storing event IDs would duplicate that information (the same reasoning as Delivery's and Customer's consumers). It would only be needed for events whose effect cannot be read from state, such as increments.

## Not done here
- The retry path (database down during an event) is configured as in Delivery but not tested here; Delivery's consumer tests cover the same error handler.
