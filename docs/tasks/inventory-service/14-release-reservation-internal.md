---
status: DONE
service: inventory-service
---
# EP-INV-07: Release Stock Reservation (Internal)

## Context
Order Service releases an order's held stock when the payment window expires or the order is cancelled. Release is idempotent and all-or-nothing for the order. When an item's available quantity rises from zero, `ProductStockReplenished` is emitted.
*References:* [Inventory Spec — EP-INV-07](../../design/services-specs/inventory-service-spec.md), [Order Spec — cancellation saga, EP-ORD-JOB-01](../../design/services-specs/order-service-spec.md), [System Scenarios (SCN-S06, SCN-S07)](../../design/scenarios/system-scenarios.md), [Reserve stock](13-reserve-stock-internal.md)

## Acceptance Criteria
- [x] `POST /inventory/reservations/{orderId}/release` returns `204 No Content` (see deviations).
- [x] No `ACTIVE` reservations for the order (never existed, already released or consumed) → `204 No Content`, nothing changed (idempotent).
- [x] Otherwise, in one transaction, for each `ACTIVE` reservation: `availableQuantity += quantity`, `reservedQuantity -= quantity`, reservation `status = RELEASED`. Partial release is impossible.
- [x] `RELEASED` and `CONSUMED` rows are never touched again; calling release twice (also concurrently) restores stock exactly once.
- [x] If an item's `availableQuantity` goes from `0` to `> 0`, `ProductStockReplenished` is published (after commit, via the publisher). No event otherwise.
- [x] After a release the same `orderId` can be reserved again (the partial unique index only covers `ACTIVE` rows).
- [x] Non-UUID `orderId` → `400`.
- [x] Route rule `POST /inventory/reservations/{orderId}/release` → `hasRole("SERVICE")`; anonymous `401`, any user role `403`.
- [x] Tests: service unit tests (single and multiple items, none active, repeated release); integration tests for the concurrent double release (stock restored once) and the replenished crossing; the `CHECK (reserved_quantity >= 0)` is never hit.

## Technical Notes / Constraints
- Lock the affected `StockItem` rows in sorted `productId` order, like reserve.
- Releasing a reservation of an order already `CONSUMED` (shipped) must not return stock: only `ACTIVE` rows are released; a mixed state is not expected, but the filter by status must make it harmless.
- Both reserve and release change stock for many products in one transaction, and so may emit several events; make sure the publisher interface supports "several events in one transaction".

## Decisions
Agreed on 2026-10-11:
- **Locking order:** first the order's `ACTIVE` reservation rows (`findByOrderIdAndStatusForUpdate`, `SELECT … FOR UPDATE`), then the `StockItem` rows sorted by `productId`. A concurrent release of the same order waits on the reservation row locks. When the first release commits, PostgreSQL re-checks `status = 'ACTIVE'` on the changed rows, so the waiting call gets an empty list and does nothing. Stock is restored exactly once.
- **No deadlock with reserve:** reserve locks only `StockItem` rows and inserts new reservation rows; it never waits on existing reservation rows. A reserve for the same order sees the `ACTIVE` rows and stops with `409` before inserting. Releases of different orders lock disjoint reservation rows and then stock rows in the same sorted order.
- The "several events in one transaction" note is already covered: the publisher is called once per crossing, and each call is delivered after commit.

## Deviations from spec / task
- Success is `204 No Content` (spec/task: `200 OK`), consistent with reserve and the other no-body endpoints. This is part of the deferred end-of-sprint spec update (see [task 13](13-reserve-stock-internal.md)).

## Implementation
- `StockItemReservationRepository.findByOrderIdAndStatusForUpdate` (`PESSIMISTIC_WRITE`, ordered by `productId`).
- `ReservationService.release(orderId)`: lock reservations → empty means no-op → lock stock rows → `available += q`, `reserved -= q`, `status = RELEASED`, `ProductStockReplenished` on a `0 → > 0` crossing.
- `ReservationController` `POST /{orderId}/release` → `204`; `SecurityConfig`: `hasRole("SERVICE")`.
- Tests: `ReservationServiceReleaseTest`, release cases in `ReservationControllerTest`, `ReleaseReservationIntegrationTest` (restore + status, unknown order, twice, 6 concurrent releases restore once and never hit the `CHECK`, `CONSUMED` rows untouched, re-reserve after release), `StockEventsIntegrationTest#releaseFromZero_publishesReplenished`.
- `support/Concurrently` test helper (shared by the reserve and release integration tests).
