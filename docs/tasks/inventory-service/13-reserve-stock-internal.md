---
status: TODO
service: inventory-service
---
# EP-INV-06: Reserve Stock (Internal)

## Context
Order Service calls this endpoint during order submission to hold stock for all items of an order, **all or nothing**. If one item is short, no stock is locked for any item. When an item's available quantity falls to zero, `ProductStockDepleted` is emitted.
*References:* [Inventory Spec — EP-INV-06](../../design/services-specs/inventory-service-spec.md), [Order Spec — EP-ORD-02](../../design/services-specs/order-service-spec.md), [Customer Scenarios (SCN-C14, SCN-C15)](../../design/scenarios/customer-scenarios.md), [Reservation persistence](12-stock-reservation-entity-and-persistence.md), [Stock events](06-stock-events-publish-and-listen.md)

## Acceptance Criteria
- [ ] `POST /inventory/reservations` accepts `{ orderId, items: [ { productId, quantity } ] }`.
- [ ] Validation → `400`: `orderId` is a UUID; `items` is non-empty (with an upper bound); every `quantity ≥ 1`; every `productId` is a UUID; a `productId` appearing twice in one request is rejected (or merged; choose, document and test).
- [ ] **Success:** `200 OK` with no response fields. In one database transaction, for each item `availableQuantity -= quantity`, `reservedQuantity += quantity`, and a `StockItemReservation(status = ACTIVE)` row is created (one per item).
- [ ] **Insufficient stock** for one or more items, including a product with no `StockItem` (treated as zero available) → `422 { insufficientItems: [ { productId, requested, available } ] }` listing **all** short items, and nothing is changed.
- [ ] The order already has `ACTIVE` reservations → `409 Conflict` and nothing is changed (idempotency guard, never double-reserve). The same `409` results when two concurrent requests for the same `orderId` race (the partial unique index from task 12 is the guard).
- [ ] Concurrent reservations of the same product never oversell: `availableQuantity` never goes below zero.
- [ ] Rows are locked in a fixed order (sorted `productId`) so two orders reserving the same products in different order cannot deadlock.
- [ ] If an item's `availableQuantity` goes from `> 0` to `0`, `ProductStockDepleted` is published through the publisher from [task 06](06-stock-events-publish-and-listen.md) (after commit). No event for other changes.
- [ ] Route rule `POST /inventory/reservations` → `hasRole("SERVICE")`; anonymous `401`, any user role `403`.
- [ ] Documentation: update EP-INV-06 in the Inventory spec, the reservation row in [api-contracts.md](../../design/services-requirements/api-contracts.md) and SCN-C14/15 in the customer scenarios: the response has **no** `reservationId` and **no** `expiresAt`; Order Service owns the TTL (`reservationExpiresAt`) and finds reservations by `orderId`. Remove the "only for `ACTIVE` products" qualifier (see task 06).
- [ ] Tests: service unit tests (success, partial shortage, missing StockItem, duplicate order, duplicate product in request); integration tests on PostgreSQL for atomicity (nothing changed on failure) and for a concurrent race (many threads reserving the last unit: exactly one succeeds); event test for the depleted crossing.

## Technical Notes / Constraints
- **Contract decision (agreed):** the spec's `{ reservationId, expiresAt }` response is dropped. A single reservation ID does not exist (an order has 1+ rows) and `StockItemReservation` has no expiry field. Order Service already computes `reservationExpiresAt`.
- A retry by Order Service after a timeout gets `409` because the first call succeeded. Document that Order must treat `409` for its own `orderId` as "already reserved".
- Check-then-update is not enough under concurrency; use the locking approach decided in [task 02](02-stock-item-entity-and-persistence.md) and prove it with the concurrent test.
- Compute "all short items" before changing anything, so the `422` body is complete and the transaction does not need to be rolled back by an exception halfway through.
- The event must be recorded only if the whole transaction commits; with the direct publisher this is the `AFTER_COMMIT` rule, with the outbox ([task 17](17-transactional-outbox.md)) it is part of the transaction.
