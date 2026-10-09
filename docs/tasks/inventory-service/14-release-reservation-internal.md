---
status: TODO
service: inventory-service
---
# EP-INV-07: Release Stock Reservation (Internal)

## Context
Order Service releases an order's held stock when the payment window expires or the order is cancelled. Release is idempotent and all-or-nothing for the order. When an item's available quantity rises from zero, `ProductStockReplenished` is emitted.
*References:* [Inventory Spec — EP-INV-07](../../design/services-specs/inventory-service-spec.md), [Order Spec — cancellation saga, EP-ORD-JOB-01](../../design/services-specs/order-service-spec.md), [System Scenarios (SCN-S06, SCN-S07)](../../design/scenarios/system-scenarios.md), [Reserve stock](13-reserve-stock-internal.md)

## Acceptance Criteria
- [ ] `POST /inventory/reservations/{orderId}/release` returns `200 OK`.
- [ ] No `ACTIVE` reservations for the order (never existed, already released or consumed) → `200 OK`, nothing changed (idempotent).
- [ ] Otherwise, in one transaction, for each `ACTIVE` reservation: `availableQuantity += quantity`, `reservedQuantity -= quantity`, reservation `status = RELEASED`. Partial release is impossible.
- [ ] `RELEASED` and `CONSUMED` rows are never touched again; calling release twice (also concurrently) restores stock exactly once.
- [ ] If an item's `availableQuantity` goes from `0` to `> 0`, `ProductStockReplenished` is published (after commit, via the publisher). No event otherwise.
- [ ] After a release the same `orderId` can be reserved again (the partial unique index only covers `ACTIVE` rows).
- [ ] Non-UUID `orderId` → `400`.
- [ ] Route rule `POST /inventory/reservations/{orderId}/release` → `hasRole("SERVICE")`; anonymous `401`, any user role `403`.
- [ ] Tests: service unit tests (single and multiple items, none active, repeated release); integration tests for the concurrent double release (stock restored once) and the replenished crossing; the `CHECK (reserved_quantity >= 0)` is never hit.

## Technical Notes / Constraints
- Lock the affected `StockItem` rows in sorted `productId` order, like reserve.
- Releasing a reservation of an order already `CONSUMED` (shipped) must not return stock: only `ACTIVE` rows are released; a mixed state is not expected, but the filter by status must make it harmless.
- Both reserve and release change stock for many products in one transaction, and so may emit several events; make sure the publisher interface supports "several events in one transaction".
