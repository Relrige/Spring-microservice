---
status: DONE
service: delivery-service
---
# EP-DEL-02: Cancel Shipment / TTN (Cancellation Saga Step)

## Context
An order can be cancelled from `PENDING_PAYMENT`, `PAID` and `PENDING_SHIPPING`. `PENDING_SHIPPING` means a TTN already exists, so cancelling a generated TTN is a normal path. That is why `CANCELLED` was added to `ShipmentStatus` (decision 2026-10-15) instead of deleting rows.

Two races matter:
- **Cancel before `OrderPaid` is consumed.** A `CANCELLED` tombstone makes the late event a no-op.
- **Cancel while the TTN job runs an attempt.** The job's optimistic check discards its result and cancels the TTN it got ([task 11](11-ttn-generation-job.md)).

*References:* [Delivery Spec — EP-DEL-02, Shipment lifecycle](../../design/services-specs/delivery-service-spec.md), [Order Spec — EP-ORD-06](../../design/services-specs/order-service-spec.md), [SCN-S07](../../design/scenarios/system-scenarios.md)

## Acceptance Criteria
- [x] `DELETE /delivery/shipments/{orderId}` (role `SERVICE`) → `204` for every outcome except `IN_TRANSIT` and `DELIVERED`.
- [x] No shipment → create a `CANCELLED` tombstone (race-safe against a concurrent `OrderPaid` insert).
- [x] `CANCELLED` → `204`, nothing changes, no carrier call.
- [x] `TTN_PENDING` / `TTN_FAILED` → `cancel()`, no carrier call.
- [x] `TTN_GENERATED` → `cancel()` and commit, then `CarrierGateway.cancel(ttn)`. On carrier failure: `ERROR` log, `failureReason = "carrier_cancellation_failed: <error>"`, `ttn` kept, still `204`.
- [x] `IN_TRANSIT` or `DELIVERED` → `409` (the parcel is already with the carrier).
- [x] Tests: each state above; repeated call; tombstone, then `OrderPaid` creates nothing; carrier failure (mock failure rate `1`) still gives `204` with the reason stored.

## Technical Notes / Constraints
- The carrier call is made outside the transaction that changed the status, so no row lock is held while the carrier is slow.
- The response changed from `200` to `204` compared with the first contract. Order's client is not written yet, so nothing breaks.

## Implementation Notes (2026-10-15)
- **Endpoint:** `DELETE /delivery/shipments/{orderId}` in `ShipmentController`, `@ResponseStatus(NO_CONTENT)`; role `SERVICE` (task 04).
- **`ShipmentService.cancel(orderId)`:**
  1. `insertIfAbsent(orderId, Shipment::cancelledTombstone)` (task 10's race-safe insert). If a concurrent `OrderPaid` insert wins, the tombstone insert fails on the unique `order_id` and the method continues with step 2, so the new pending shipment is cancelled.
  2. One transaction decides by status: `CANCELLED` → nothing; `IN_TRANSIT` / `DELIVERED` → `InvalidShipmentStateException` (`409`, "Cannot cancel a shipment that was handed to the carrier"); otherwise `shipment.cancel()`, and for `TTN_GENERATED` the TTN is returned for step 3. If the TTN job commits a result at the same moment, the optimistic lock fails; the step is retried (up to 3 times) on the new state.
  3. After the commit (no row lock held): `CarrierGateway.cancel(ttn)`. On `CarrierException`: `ERROR` log, `recordCarrierCancellationFailure` stores `carrier_cancellation_failed: <error>` in a new transaction, the `ttn` stays, and the response is still `204`.
- A TTN attempt that runs while a `TTN_PENDING` shipment is cancelled discards its result and cancels its TTN (task 11).
- **Verified (2026-10-15):** `CancelShipmentIntegrationTest` (Testcontainers, a spied mock carrier, TTN job off): tombstone, then `OrderPaid` creates nothing; `TTN_PENDING` / `TTN_FAILED` cancelled without a carrier call; `TTN_GENERATED` cancelled and the TTN cancelled at the carrier; carrier failure → `204` with the reason stored and the TTN kept; repeated call → `204` and only one carrier call; `IN_TRANSIT` / `DELIVERED` → `409` unchanged; a concurrent cancel and `OrderPaid` end `CANCELLED`; non-UUID → `400`; anonymous `401`, worker `403`.
