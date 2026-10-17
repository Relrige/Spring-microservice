---
status: DONE
service: order-service
---
# Order Entity and Persistence

## Context
`Order` is the aggregate root: one row per order with the delivery details, totals and status, plus its `OrderItemSnapshot` lines with the price and title **at order time**. Snapshots never change after placement. Every status change is a check-and-set guarded by an optimistic-lock version, because payment, cancellation, expiry and Delivery's events can touch the same order at the same moment.
*References:* [Order Spec — Service Overview, Concurrency](../../design/services-specs/order-service-spec.md), [Entities — Order](../../design/entities/entities.md), [Order State Machine](../../design/entities/order-state-machine.md)

## Acceptance Criteria
- [x] Flyway `V1__create_orders_tables.sql`:
  - `orders`: `id` (UUID PK), `customer_id` (UUID, **not null**), `customer_email` (not null), `status` (`CHECK` on the 8 states), `total_amount` (`NUMERIC(12,2)`, `CHECK >= 0`), `currency` (`VARCHAR(3)`), `delivery_name`, `delivery_phone`, `delivery_address`, `delivery_carrier` (not null), `reservation_expires_at`, `placed_at`, `paid_at` (nullable), `status_before_cancellation` (nullable), `cancellation_attempts` (default 0), `cancellation_failure` (nullable), `needs_manual_attention` (default false), `version` (not null).
  - `order_items`: `id`, `order_id` (FK), `product_id`, `product_title`, `unit_price_at_order` (`NUMERIC(12,2)`, `CHECK >= 0`), `quantity` (`CHECK >= 1`); unique `(order_id, product_id)` (one line per product, as Inventory requires).
  - Indexes for later queries: `(customer_id, placed_at DESC)` (history), `(status, reservation_expires_at)` (expiry job), `(status, paid_at)` (fulfillment queue), a partial index on `status = 'CANCELLATION_REQUESTED'` (cancellation job).
- [x] Entities `OrderEntity` with `OrderItemEntity` (or an `@ElementCollection` of a value type — decide and note why), enum `OrderStatus`, `@Embeddable DeliveryDetails`, `@Version`. Snapshot fields have no setters.
- [x] Status changes only through intent-revealing methods that enforce the state machine (e.g. `markPaid(now)`, `requestCancellation()`, `markCancelled()`, `expire()`, `markPendingShipping()`, `markShipped()`, `markDelivered()`). An illegal transition throws; it is never silently ignored.
- [x] Hibernate `validate` passes.
- [x] Repository methods for later tasks: find by `id` **and** `customerId` (ownership in one query), page by `customerId`, find by statuses ordered by `paid_at`, find expired candidates, find cancellations to resume.
- [x] Tests on PostgreSQL: save and read back with items; the state-machine methods (every allowed transition and a sample of forbidden ones, as unit tests); two concurrent updates of one order → one `OptimisticLockingFailureException`; the `CHECK` constraints reject a negative price and quantity 0.

## Technical Notes / Constraints
- Money is `BigDecimal`, time is `Instant` (`TIMESTAMPTZ`); currency is `UAH` for now.
- The delivery details are personal data and are also copied into `OrderPaid`; do not log them.
- `version` makes every check-and-set safe without row locks held across remote calls (see the spec's *Concurrency and consistency*).

## Implementation
Done on 2026-10-16:
- `V1__create_orders_tables.sql`: `orders` and `order_items` as listed, with `CHECK`s on status, amounts, currency format and quantity; `chk_orders_paid_at_once_paid` (a paid, shipped or delivered order has `paid_at`); unique `(order_id, product_id)`; the four indexes plus `idx_order_items_order`.
- **Extra column `cancellation_last_attempt_at`:** the retry job of task 12 needs the time of the last saga attempt for its back-off. Added to the spec's entity list and `entities.md`.
- `entities/OrderEntity`: the ID is assigned by Order (not generated), because Inventory reserves under it before the order is stored; `@Version Long` is null until the first save, which is how Spring Data recognises a new order with an assigned ID. The factory `place(...)` computes `totalAmount` from the snapshots and rejects an empty order and a duplicate product. Transitions `markPaid`, `markPendingShipping`, `markShipped` (also from `PAID`, for a late `TTNGenerated`), `markDelivered`, `expire`, `requestCancellation` (remembers `statusBeforeCancellation`), `markCancelled`, `recordCancellationFailure(failure, now, manualAttentionAfter)`; queries `refundOwed()` and `belongsTo(customerId)`. Every illegal transition throws `InvalidOrderStateException`.
- **Items as a child entity**, not an `@ElementCollection`: the spec gives each snapshot an `id`, the lines are written once and never changed, and a unidirectional `@OneToMany` with `@JoinColumn(nullable = false, updatable = false)` inserts them with the foreign key set. An element collection would delete and re-insert every line on any change of the collection.
- `DeliveryDetails` is an `@Embeddable` without a generated `toString()` (personal data).
- `OrderRepository`: `findByIdAndCustomerId` (with items), `findByCustomerId(Pageable)`, `findByStatusInOrderByPaidAtAsc`, `findExpired(expiredBefore, Limit)`, `findInterruptedCancellations(Limit)` (never-retried first).
- Tests: `OrderEntityTest` (17: totals and window, invalid input, every allowed transition, cancellation from each status with `refundOwed`, rejection from the others, failure counting and the manual-attention flag, forbidden transitions leave the status unchanged) and `OrderRepositoryTest` (11 on PostgreSQL 18: round trip with items, the ownership query, paging newest first, fulfillment order, expiry candidates, interrupted cancellations, two concurrent updates → `ObjectOptimisticLockingFailureException` with the first one winning, `CHECK` and unique constraints).
