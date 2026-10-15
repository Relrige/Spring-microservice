---
status: DONE
service: delivery-service
---
# EP-DEL-EVT-02 + EP-DEL-04: Consume `OrderShipped` and Handle the Carrier's Delivery Report

## Context
VoltStore's carrier is an in-process mock, so there is no webhook (decision 2026-10-15). Delivery is simulated the way it happens in reality, triggered by the handover:
1. The worker confirms the courier collected the parcel, and Order publishes `OrderShipped`.
2. Delivery marks the shipment `IN_TRANSIT` and calls `CarrierGateway.handOver(ttn)`.
3. A configurable delay later, the mock carrier reports the delivery through the inbound port `CarrierEventHandler.onDelivered(ttn)`.
4. Delivery marks the shipment `DELIVERED` and emits `OrderDelivered`.

This removes the public endpoint and its security risk. It also guarantees that a parcel is never reported delivered before it was shipped. The inbound port is also where a real webhook adapter would plug in later ([task 17](17-future-carrier-simulator-and-webhook-signature.md)).
*References:* [Delivery Spec — EP-DEL-EVT-02, EP-DEL-04, Carrier Gateway, Consuming Order events](../../design/services-specs/delivery-service-spec.md), [SCN-S08](../../design/scenarios/system-scenarios.md), [Order Spec — EP-ORD-08, EP-ORD-EVT-02](../../design/services-specs/order-service-spec.md), [Carrier port and mock](07-carrier-gateway-port-and-mock.md)

## Acceptance Criteria
- [x] `OrderShipped` is handled by the existing Order-events listener from [task 10](10-consume-order-paid.md) (dispatch by `eventType`); the payload is read into Delivery's own record (`orderId`, `ttn`), unknown fields ignored.
- [x] Handling `OrderShipped`: `TTN_GENERATED` → `markInTransit(now)` and commit, then `CarrierGateway.handOver(ttn)`. `IN_TRANSIT` / `DELIVERED` → acknowledge (duplicate). Not found or any other status → `WARN`, acknowledge. An event `ttn` different from the shipment's → `WARN`, the shipment's `ttn` is used.
- [x] `CarrierEventHandler` implemented by a Delivery service: `onDelivered(ttn)` looks the shipment up by `ttn`. `IN_TRANSIT` → `markDelivered()` and `OrderDelivered` appended to the outbox in one transaction. `DELIVERED` → no-op. Unknown TTN or any other status → `WARN`, no change. Database errors propagate, so the mock reschedules the report.
- [x] Restart recovery: on `ApplicationReadyEvent`, call `handOver(ttn)` for every `IN_TRANSIT` shipment and log how many were re-announced.
- [x] Tests (Testcontainers Kafka + PostgreSQL, `delivery-delay` of about 1 s or a controllable scheduler):
  - `OrderShipped` → `IN_TRANSIT` with `handedOverAt`, then `DELIVERED` and exactly one `OrderDelivered` (key, headers, payload);
  - the same `OrderShipped` twice → one handover, one event;
  - `OrderShipped` for a shipment that is not `TTN_GENERATED` (pending, failed, cancelled) → no change;
  - `onDelivered` called twice, and for an unknown TTN → one event, no error;
  - restart recovery: an `IN_TRANSIT` row present at startup ends `DELIVERED`;
  - `DELETE /delivery/shipments/{orderId}` on an `IN_TRANSIT` shipment → `409` (from [task 13](13-cancel-shipment.md)).

## Technical Notes / Constraints
- `handOver` is called after the commit, not inside the transaction. If the pod dies in between, the startup recovery covers it.
- With several replicas, each one re-announces at startup and more than one mock may report the same delivery. `onDelivered` is idempotent, and the outbox write happens only on the actual `IN_TRANSIT → DELIVERED` transition, so `OrderDelivered` is emitted once.
- `onDelivered` runs on the mock's scheduler thread, not on a request or listener thread; give it its own transaction and logging context (orderId, TTN).
- Order Service does not publish `OrderShipped` yet; tests publish it with header `eventType: OrderShipped`, as Inventory's tests do.

## Implementation Notes (2026-10-15)
- **Dispatch:** the existing `OrderEventsListener` (task 10) now also handles `eventType: OrderShipped`, read into `events/OrderShippedEvent(orderId, ttn?)` (unknown fields ignored, `orderId` required; a broken payload is a poison message → DLT).
- **`ShipmentService.handOver(event)`:** one transaction decides: `TTN_GENERATED` → `markInTransit(clock.instant())` (a differing `ttn` in the event → `WARN`, the shipment's own TTN is used); `IN_TRANSIT` / `DELIVERED` → `INFO`, duplicate; not found or any other status → `WARN`. After the commit, `CarrierGateway.handOver(ttn)`. A concurrent state change makes the optimistic lock fail; the Kafka error handler then retries the record, which decides again on the new state.
- **`services/DeliveryReportService implements CarrierEventHandler`:** `onDelivered(ttn)` runs in its own transaction with a fresh correlation id in the MDC (it is called on the mock's scheduler thread): lookup by TTN; `IN_TRANSIT` → `markDelivered()` + `OrderDelivered` via the outbox; `DELIVERED` → nothing; unknown TTN or other status → `WARN`. Database errors propagate, so the mock reschedules the report (task 07).
- **`services/HandoverRecovery`:** on `ApplicationReadyEvent`, `handOver(ttn)` for every `IN_TRANSIT` shipment, logging how many were handed over again. With several replicas each one does it; the report is idempotent, so `OrderDelivered` is still written once (only on the real `IN_TRANSIT → DELIVERED` transition).
- **Fix found while testing:** the mock carrier now uses a thread-safe `SecureRandom` (separate commit, noted in task 07).
- **Verified (2026-10-15):** `HandoverAndDeliveryIntegrationTest` (Testcontainers Kafka + PostgreSQL, `delivery-delay=1s`, a spied mock carrier, TTN job off, the outbox relay running): `OrderShipped` → `IN_TRANSIT` with `handedOverAt`, then `DELIVERED` and exactly one `OrderDelivered` on `delivery.shipment-events` (key `orderId`, `eventType`/`eventId` headers, payload `{eventId, orderId, ttn, timestamp}`); the same `OrderShipped` twice → one handover, one event; `OrderShipped` for `TTN_PENDING` / `TTN_FAILED` / `CANCELLED` → no change; a different TTN in the event → the shipment's TTN is handed over; `onDelivered` twice and for an unknown TTN → one event, no error; a report for a `TTN_GENERATED` shipment changes nothing; restart recovery: an `IN_TRANSIT` row the carrier did not know ends `DELIVERED`; `DELETE` on `IN_TRANSIT` → `409`. The full module suite (18 classes) passes.
