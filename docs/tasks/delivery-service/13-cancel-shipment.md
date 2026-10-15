---
status: TODO
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
- [ ] `DELETE /delivery/shipments/{orderId}` (role `SERVICE`) → `204` for every outcome except `IN_TRANSIT` and `DELIVERED`.
- [ ] No shipment → create a `CANCELLED` tombstone (race-safe against a concurrent `OrderPaid` insert).
- [ ] `CANCELLED` → `204`, nothing changes, no carrier call.
- [ ] `TTN_PENDING` / `TTN_FAILED` → `cancel()`, no carrier call.
- [ ] `TTN_GENERATED` → `cancel()` and commit, then `CarrierGateway.cancel(ttn)`. On carrier failure: `ERROR` log, `failureReason = "carrier_cancellation_failed: <error>"`, `ttn` kept, still `204`.
- [ ] `IN_TRANSIT` or `DELIVERED` → `409` (the parcel is already with the carrier).
- [ ] Tests: each state above; repeated call; tombstone, then `OrderPaid` creates nothing; carrier failure (mock failure rate `1`) still gives `204` with the reason stored.

## Technical Notes / Constraints
- The carrier call is made outside the transaction that changed the status, so no row lock is held while the carrier is slow.
- The response changed from `200` to `204` compared with the first contract. Order's client is not written yet, so nothing breaks.
