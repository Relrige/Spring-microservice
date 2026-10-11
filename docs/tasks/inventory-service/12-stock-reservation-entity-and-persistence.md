---
status: DONE
service: inventory-service
---
# StockItemReservation Entity, Status Enum, Repository and Migration

## Context
A reservation records stock held for an order. An order has **one or more** reservation rows (one per product line). Order Service owns the payment-window TTL, so reservations carry no expiry; they are found and changed by `orderId`.
*References:* [Inventory Spec — Service Overview](../../design/services-specs/inventory-service-spec.md), [Domain Entities — Inventory](../../design/entities/entities.md), [StockItem persistence](02-stock-item-entity-and-persistence.md)

## Acceptance Criteria
- [x] Create enum `ReservationStatus { ACTIVE, RELEASED, CONSUMED }` and entity `StockItemReservation`: `id` (UUID, generated), `orderId`, `productId`, `quantity`, `status` (stored as string).
- [x] Flyway migration `V2__create_stock_item_reservations_table.sql`: primary key; foreign key `product_id → stock_items(product_id)`; `CHECK (quantity >= 1)`; index on `(order_id, status)` for the lookups in reserve/release/ship; a **partial unique index** on `(order_id, product_id) WHERE status = 'ACTIVE'` so an order can hold at most one active reservation per product.
- [x] `StockItemReservationRepository` with `findByOrderIdAndStatus(orderId, ACTIVE)` and an existence check for active reservations of an order.
- [x] Repository test (real PostgreSQL): persistence, the partial unique index (second active row for the same order and product fails, a `RELEASED` row for the same pair is allowed), `quantity < 1` rejected, FK to a missing `StockItem` rejected.

## Technical Notes / Constraints
- No `expiresAt`, no `reservationId` in any API response: the draft's expiry column is intentionally dropped (Order Service owns the TTL; the product decision is recorded in [task 13](13-reserve-stock-internal.md)).
- The partial unique index is the database guard for the "order already has an `ACTIVE` reservation → `409`" rule under concurrent requests.
- Reservation rows are history: never delete them. `RELEASED` and `CONSUMED` are terminal states.
- Use the entity convention from [task 01](01-rework-draft-to-spec-foundation.md).
- The partial unique index only conflicts when two `ACTIVE` rows share an order **and** a product. Two concurrent reserve calls for the same order with completely different product sets would not collide on it. Open point for [task 13](13-reserve-stock-internal.md): Order Service retries with the same items, so this is probably acceptable, but decide and document it there.
- Integration tests that clean `stock_items` with `DELETE` will have to delete `stock_item_reservations` first once task 13 creates real reservation rows (FK).

## Deviations from spec / task
Agreed on 2026-10-11:
- The entity is named `StockItemReservationEntity` (task: `StockItemReservation`) to match the `StockItemEntity` convention.
- `productId` is a plain UUID column, not a JPA `@ManyToOne` association. Reserve/release lock `StockItem` rows themselves; the database foreign key keeps integrity.
- Added `created_at` and `updated_at` (`TIMESTAMP WITH TIME ZONE`, Hibernate `@CreationTimestamp` / `@UpdateTimestamp`, DB default `now()`), which are not in the entity spec. Rows are kept forever as history, and `updated_at` shows when a reservation was released or consumed.
- Added `CHECK (status IN ('ACTIVE', 'RELEASED', 'CONSUMED'))` so invalid status strings cannot be stored by raw SQL.

## Implementation
- `entities/ReservationStatus`, `entities/StockItemReservationEntity` (`@GeneratedValue(strategy = UUID)`, `@Enumerated(STRING)`, status defaults to `ACTIVE`; `orderId`, `productId`, `quantity` are not updatable).
- `db/migration/V2__create_stock_item_reservations_table.sql`: table, FK, checks, index `idx_stock_item_reservations_order_status`, partial unique index `uq_stock_item_reservations_active_order_product`.
- `repositories/StockItemReservationRepository`: `findByOrderIdAndStatus`, `existsByOrderIdAndStatus`.
- Test: `StockItemReservationRepositoryTest` (PostgreSQL Testcontainer).
