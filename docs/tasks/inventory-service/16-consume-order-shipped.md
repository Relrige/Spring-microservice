---
status: IN_PROGRESS
service: inventory-service
---
# EP-INV-EVT-02: Consume `OrderShipped`

## Context
When the warehouse hands the parcel to the carrier, Order Service emits `OrderShipped`. Inventory then converts the order's held stock into a permanent deduction: `reservedQuantity` goes down, reservations become `CONSUMED`. `availableQuantity` is untouched (it was reduced at reservation time).

Order Service may not exist yet; tests publish the event with the payload from the spec.
*References:* [Inventory Spec — EP-INV-EVT-02](../../design/services-specs/inventory-service-spec.md), [Event Catalogue — `OrderShipped`](../../design/services-requirements/events-catalogue.md), [Reserve stock](13-reserve-stock-internal.md)

## Acceptance Criteria
- [x] `@KafkaListener` on the Order-owned topic (name from configuration), consumer group `inventory-service`.
- [x] Own record for the payload `{ orderId, ttn }` (tolerant reader; extra fields such as `customerEmail` are ignored).
- [x] In one transaction, for every `ACTIVE` reservation of the order: `reservedQuantity -= quantity` and `status = CONSUMED`. `availableQuantity` and the other fields are unchanged. No stock event is emitted.
- [x] No `ACTIVE` reservations (already consumed, released, or never existed) → log a warning and acknowledge; no error.
- [x] Duplicate delivery, also concurrent, deducts exactly once.
- [x] A reservation that has been `RELEASED` is never consumed (a shipped event after a cancellation does not touch stock).
- [x] Malformed payloads and transient failures follow the same dead-letter and retry policy as [task 15](15-consume-product-created.md); missing topic is not fatal.
- [ ] Tests (Testcontainers Kafka + PostgreSQL): multi-item order consumed; unknown order warns and acknowledges; duplicate event is a no-op; released reservation stays released; `CHECK (reserved_quantity >= 0)` never violated.

## Technical Notes / Constraints
- Idempotency comes from the status filter (`ACTIVE` only), not from an extra "processed events" table; discuss when such a table would be needed (non-state-based handlers).
- Lock the stock rows in sorted `productId` order as in reserve/release.
- Think about ordering relative to `OrderCancelled` (not consumed here): the Order state machine forbids cancelling a shipped order, so no extra guard is needed beyond the status filter.

## Starting Point (from tasks 01–14, collected 2026-10-12)
- **Reuse the release locking** ([task 14](14-release-reservation-internal.md)): `StockItemReservationRepository.findByOrderIdAndStatusForUpdate(orderId, ACTIVE)` locks the order's `ACTIVE` rows first; then `StockItemRepository.findAllByIdsForUpdate` locks the stock rows sorted by `productId`. A concurrent duplicate event waits on the reservation rows and then finds none, so the deduction happens exactly once. Using the same lock order as release also means consume and release of one order cannot deadlock.
- **Where:** a `consume(orderId)` method next to `reserve` and `release` in `ReservationService`.
- **No event:** consuming does not change `availableQuantity`, so `StockEvents.forTransition` is not called.
- Listener error handling, topic-name property and missing-topic behaviour: reuse what [task 15](15-consume-product-created.md) sets up.
- **Spec:** EP-INV-EVT-02 was updated at sprint end with this lock order.

## Implementation Notes (2026-10-12)
- **Code:** `events/OrderShippedListener` → `ReservationService.consume(orderId)`, which locks the order's `ACTIVE` reservations, then the stock rows sorted by `productId` (the release lock order), decrements `reservedQuantity`, sets `CONSUMED`, and returns the count (0 → warning in the listener). No stock event. Own record `events/OrderShippedEvent(orderId, ttn?)`.
- **Topic (provisional):** `inventory.kafka.topics.order-events` = `order.order-events`, following the `<service>.<aggregate>-events` convention; Order Service has not published yet, so align the name when it does. Recorded in the Event Catalogue.
- **Event-type filter (contract with Order):** the topic will carry all order events (`OrderPlaced`, `OrderCancelled`, …), so only records with header `eventType: OrderShipped` are consumed (`KafkaConfig.orderShippedOnly`). Without the filter, any order event would be read as "shipped" and consume the reservations.
- **Processed-events table, when needed:** not needed here: the `ACTIVE` status filter makes the handler idempotent (state-based). A table of processed `eventId`s is needed when the effect is not visible in the state, e.g. "add N to a counter" or "send an e-mail".
- Errors, ack mode and missing topic: as in task 15 (DLT `inventory.order.order-events.dlt`).
- **Open:** `ConsumedEventsIntegrationTest` (multi-item order, duplicate, unknown order, released order, other event type, concurrent duplicates) is written but **not run yet** (Docker unavailable). Unit tests (`ReservationServiceConsumeTest`) pass.
