---
status: TODO
service: inventory-service
---
# StockItemReservation Entity, Status Enum, Repository and Migration

## Context
A reservation records stock held for an order. An order has **one or more** reservation rows (one per product line). Order Service owns the payment-window TTL, so reservations carry no expiry; they are found and changed by `orderId`.
*References:* [Inventory Spec — Service Overview](../../design/services-specs/inventory-service-spec.md), [Domain Entities — Inventory](../../design/entities/entities.md), [StockItem persistence](02-stock-item-entity-and-persistence.md)

## Acceptance Criteria
- [ ] Create enum `ReservationStatus { ACTIVE, RELEASED, CONSUMED }` and entity `StockItemReservation`: `id` (UUID, generated), `orderId`, `productId`, `quantity`, `status` (stored as string).
- [ ] Flyway migration `V2__create_stock_item_reservations_table.sql`: primary key; foreign key `product_id → stock_items(product_id)`; `CHECK (quantity >= 1)`; index on `(order_id, status)` for the lookups in reserve/release/ship; a **partial unique index** on `(order_id, product_id) WHERE status = 'ACTIVE'` so an order can hold at most one active reservation per product.
- [ ] `StockItemReservationRepository` with `findByOrderIdAndStatus(orderId, ACTIVE)` and an existence check for active reservations of an order.
- [ ] Repository test (real PostgreSQL): persistence, the partial unique index (second active row for the same order and product fails, a `RELEASED` row for the same pair is allowed), `quantity < 1` rejected, FK to a missing `StockItem` rejected.

## Technical Notes / Constraints
- No `expiresAt`, no `reservationId` in any API response: the draft's expiry column is intentionally dropped (Order Service owns the TTL; the product decision is recorded in [task 13](13-reserve-stock-internal.md)).
- The partial unique index is the database guard for the "order already has an `ACTIVE` reservation → `409`" rule under concurrent requests.
- Reservation rows are history: never delete them. `RELEASED` and `CONSUMED` are terminal states.
- Use the entity convention from [task 01](01-rework-draft-to-spec-foundation.md).
