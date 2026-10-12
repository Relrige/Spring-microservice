---
status: DONE
service: inventory-service
---
# EP-INV-06: Reserve Stock (Internal)

## Context
Order Service calls this endpoint during order submission to hold stock for all items of an order, **all or nothing**. If one item is short, no stock is locked for any item. When an item's available quantity falls to zero, `ProductStockDepleted` is emitted.
*References:* [Inventory Spec — EP-INV-06](../../design/services-specs/inventory-service-spec.md), [Order Spec — EP-ORD-02](../../design/services-specs/order-service-spec.md), [Customer Scenarios (SCN-C14, SCN-C15)](../../design/scenarios/customer-scenarios.md), [Reservation persistence](12-stock-reservation-entity-and-persistence.md), [Stock events](06-stock-events-publish-and-listen.md)

## Acceptance Criteria
- [x] `POST /inventory/reservations` accepts `{ orderId, items: [ { productId, quantity } ] }`.
- [x] Validation → `400`: `orderId` is a UUID; `items` is non-empty (with an upper bound); every `quantity ≥ 1`; every `productId` is a UUID; a `productId` appearing twice in one request is rejected (decided: reject, not merge).
- [x] **Success:** `204 No Content` (see deviations). In one database transaction, for each item `availableQuantity -= quantity`, `reservedQuantity += quantity`, and a `StockItemReservation(status = ACTIVE)` row is created (one per item).
- [x] **Insufficient stock** for one or more items, including a product with no `StockItem` (treated as zero available) → `422 { insufficientItems: [ { productId, requested, available } ] }` listing **all** short items, and nothing is changed.
- [x] The order already has `ACTIVE` reservations → `409 Conflict` and nothing is changed (idempotency guard, never double-reserve). The same `409` results when two concurrent requests for the same `orderId` race (guarded by lock-then-check; the partial unique index from task 12 is the last-resort data guard).
- [x] Concurrent reservations of the same product never oversell: `availableQuantity` never goes below zero.
- [x] Rows are locked in a fixed order (sorted `productId`) so two orders reserving the same products in different order cannot deadlock.
- [x] If an item's `availableQuantity` goes from `> 0` to `0`, `ProductStockDepleted` is published through the publisher from [task 06](06-stock-events-publish-and-listen.md) (after commit). No event for other changes.
- [x] Route rule `POST /inventory/reservations` → `hasRole("SERVICE")`; anonymous `401`, any user role `403`.
- [ ] Documentation: update EP-INV-06 in the Inventory spec, the reservation row in [api-contracts.md](../../design/services-requirements/api-contracts.md) and SCN-C14/15 in the customer scenarios: the response has **no** `reservationId` and **no** `expiresAt`; Order Service owns the TTL (`reservationExpiresAt`) and finds reservations by `orderId`. Remove the "only for `ACTIVE` products" qualifier (see task 06). *(Deferred by agreement to the end-of-sprint spec update. Done on 2026-10-12 for the Inventory spec and `api-contracts.md`; SCN-C14/15 in the customer scenarios still mention `{reservationId, expiresAt}` and remain to be updated.)*
- [x] Tests: service unit tests (success, partial shortage, missing StockItem, duplicate order, duplicate product in request); integration tests on PostgreSQL for atomicity (nothing changed on failure) and for a concurrent race (many threads reserving the last unit: exactly one succeeds); event test for the depleted crossing.

## Technical Notes / Constraints
- **Contract decision (agreed):** the spec's `{ reservationId, expiresAt }` response is dropped. A single reservation ID does not exist (an order has 1+ rows) and `StockItemReservation` has no expiry field. Order Service already computes `reservationExpiresAt`.
- A retry by Order Service after a timeout gets `409` because the first call succeeded. Document that Order must treat `409` for its own `orderId` as "already reserved".
- Check-then-update is not enough under concurrency; use the locking approach decided in [task 02](02-stock-item-entity-and-persistence.md) and prove it with the concurrent test.
- Compute "all short items" before changing anything, so the `422` body is complete and the transaction does not need to be rolled back by an exception halfway through.
- The event must be recorded only if the whole transaction commits; with the direct publisher this is the `AFTER_COMMIT` rule, with the outbox ([task 17](17-transactional-outbox.md)) it is part of the transaction.

## Decisions
Agreed on 2026-10-11:
- **Duplicate `productId` in one request → `400`** (custom `@UniqueProductIds` constraint, reported under `errors.items`). An order has one line per product, so a duplicate is a caller bug; merging would hide it.
- **Item limit:** at most 100 items per request (`ReserveStockRequest.MAX_ITEMS`).
- **Concurrency guard for one order:** the existence check for `ACTIVE` reservations runs **after** the `StockItem` rows are locked. An order's item list cannot change, so a repeated call for the same order (for example a retry after a timeout) always has the same items. It waits on the same row locks and then sees the committed reservations of the first call, so it gets a clean `409`. This check is the only source of the `409`. The partial unique index stays as a last-resort data guard without special handling: a violation would mean a broken invariant, so it surfaces as a logged `500` rather than a misleading `409`. A `try/catch` mapping it to `409` was added first and then removed as unreachable; it would also have hidden FK/`CHECK` violations as `409`. A per-order advisory lock (`pg_advisory_xact_lock`) was considered and rejected: it would only matter for two different item lists for one order, which the application cannot produce.
- **For Order Service:** a `409` for its own `orderId` means "already reserved" (for example, the first call succeeded but its response was lost). Order should treat it as success, not as a failure. This should go into the Order Service reservation client task when it is written.

## Deviations from spec / task
- Success is `204 No Content` (task: `200 OK` with no fields; spec: `200 { reservationId, expiresAt }`). Consistent with the other no-body endpoints (readiness, set packaging).
- The response has no `reservationId` and no `expiresAt` (agreed contract decision above).
- Not done in this task, by agreement: updating EP-INV-06 in the Inventory spec, the reservation row in `api-contracts.md` and SCN-C14/15. They should say: `204` with no body, no `reservationId` / `expiresAt`, Order Service owns the TTL and finds reservations by `orderId`, and the "only for `ACTIVE` products" qualifier is removed.

## Implementation
- `dto/ReserveStockRequest` + `dto/ReserveStockItem` (Bean Validation), `validation/UniqueProductIds` + validator.
- `services/ReservationService.reserve(orderId, quantities)`: (1) lock rows sorted by `productId` (`findAllByIdsForUpdate`); (2) `existsByOrderIdAndStatus(ACTIVE)` → `ReservationAlreadyExistsException`; (3) collect all shortages (missing `StockItem` = 0 available) → `InsufficientStockForReservationException`; (4) move quantities, publish `ProductStockDepleted` on a `> 0 → 0` crossing (delivered after commit), `saveAll` the `ACTIVE` rows.
- `controllers/ReservationController` `POST /inventory/reservations` → `204`; `SecurityConfig`: `hasRole("SERVICE")`.
- Tests: `ReservationServiceTest`, `ReservationControllerTest`, `ReserveStockIntegrationTest` (persistence, atomicity, `409`, last-unit race, concurrent same-order retry, opposite-order deadlock check), `StockEventsIntegrationTest#reserveLastUnits_publishesDepletedOnlyForProductThatReachesZero`.
- Integration and repository tests now delete `stock_item_reservations` before `stock_items` (FK), and `RouteSecurityRulesTest` mocks `ReservationService`.
