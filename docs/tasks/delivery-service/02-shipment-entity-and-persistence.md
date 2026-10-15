---
status: DONE
service: delivery-service
---
# Shipment Entity and Persistence

## Context
`Shipment` is Delivery's only aggregate: one row per order, carrying the recipient snapshot from `OrderPaid`, the order items (needed to re-fetch packaging on a retry), the computed parcel, the TTN and the status. Shipments are never deleted. A `CANCELLED` row without recipient data (a *tombstone*) is how a cancellation that arrives before `OrderPaid` stops a shipment from ever being created.

The status rules live in the entity, so every later task (consumers, job, cancel, retry, carrier report) changes state through the same checked methods.
*References:* [Delivery Spec — Service Overview, Shipment lifecycle](../../design/services-specs/delivery-service-spec.md), [Domain Entities — Shipment](../../design/entities/entities.md)

## Acceptance Criteria
- [x] Flyway `V1` creates `shipments` and `shipment_items`:
  - `shipments`: `id` (UUID PK), `order_id` (UUID, **unique**), `status`, `ttn` (unique when not null), `failure_reason`, `recipient_name`, `recipient_phone`, `recipient_address`, `carrier`, `total_weight_grams`, `length_cm`, `width_cm`, `height_cm`, `declared_value` (numeric), `currency`, `handed_over_at`, `attempts` (int, default 0), `next_attempt_at`, `version`, `created_at`, `updated_at`.
  - `shipment_items`: `shipment_id` (FK), `product_id`, `quantity`; primary key `(shipment_id, product_id)`.
- [x] Database guards: `CHECK` on the status values; `quantity >= 1`; parcel values null or `> 0`; `declared_value >= 0`; recipient, carrier, declared value and currency are required unless `status = 'CANCELLED'` (tombstone).
- [x] Partial index for the TTN job: due `TTN_PENDING` rows by `next_attempt_at`.
- [x] `Shipment` entity with `@Version` and `ShipmentItem` as an `@ElementCollection` (or a child entity; document the choice). `ShipmentStatus` enum.
- [x] Factory methods `Shipment.pending(...)` (from `OrderPaid`) and `Shipment.cancelledTombstone(orderId)`.
- [x] Transition methods enforce the [lifecycle](../../design/services-specs/delivery-service-spec.md): `markGenerated(ttn, parcel)`, `recordFailedAttempt(reason, nextAttemptAt)`, `markFailed(reason)`, `cancel()`, `markInTransit(handedOverAt)`, `markDelivered()` (only from `IN_TRANSIT`). A forbidden transition throws a domain exception (mapped to `409` in task 03).
- [x] `ShipmentRepository`: `findByOrderId`, `findByTtn`, `findAllByOrderIdIn`, `findAllByStatus` (for the `IN_TRANSIT` restart recovery). Race-safe creation and the job's claim query are added by tasks 10 and 11.
- [x] Tests: unit tests for every allowed and forbidden transition; a Testcontainers repository test proves the schema matches the entity (`validate`), the unique `order_id`, the tombstone constraint, and that the version increases on update.

## Technical Notes / Constraints
- `orderId` is the natural key used by every caller; `id` stays as a surrogate key so the entity does not depend on Order's identifiers.
- Do not store packaging per item. Only the computed parcel is kept; packaging is always re-fetched (it may have been fixed by a worker).
- Store timestamps as `timestamptz` (`Instant`).

## Implementation Notes (2026-10-15)
- **Schema (`V1__create_shipments_tables.sql`):** `shipments` with `CHECK`s on status, parcel values (`> 0`), `declared_value >= 0`, `attempts >= 0`, and `shipments_snapshot_required` (recipient, carrier, declared value and currency are required unless `status = 'CANCELLED'`). `ttn` is `UNIQUE` (PostgreSQL allows several NULLs). Partial index `idx_shipments_ttn_pending_due (next_attempt_at) WHERE status = 'TTN_PENDING'`. `shipment_items` with PK `(shipment_id, product_id)` and `quantity >= 1`.
- **Items as `@ElementCollection` of an `@Embeddable` record `ShipmentItem`** (not a child entity): items have no identity or lifecycle of their own, never change after creation, and are always read with the shipment. The collection is lazy; code that needs the items outside a transaction loads them inside one first (task 11). `Shipment.pending` merges lines of the same product, so the primary key cannot be violated by an order that lists a product twice.
- **Entity convention** as in Catalog's `Product`: `@Getter`, protected no-arg constructor, factory methods, no setters. Timestamps via Hibernate `@CreationTimestamp` / `@UpdateTimestamp`.
- **Transitions:** `markGenerated(ttn, parcel)` (from `TTN_PENDING` or `TTN_FAILED`), `recordFailedAttempt(reason, nextAttemptAt)` (`TTN_PENDING` only), `markFailed(reason)` (from `TTN_PENDING`, or `TTN_FAILED` to replace the reason after a failed manual retry), `cancel()` (from `TTN_PENDING`, `TTN_FAILED`, `TTN_GENERATED`; `CANCELLED` is rejected, so the service handles the idempotent case explicitly), `recordCarrierCancellationFailure(error)` (only `CANCELLED`, used by task 13), `markInTransit(handedOverAt)`, `markDelivered()`. A forbidden transition throws `InvalidShipmentStateException` (mapped to `409` in task 03).
- **`attempts`** counts failed attempts (`recordFailedAttempt` and `markFailed` increment it); `next_attempt_at` is set to `NULL` when the shipment leaves `TTN_PENDING`, so it is nullable. `failureReason` is truncated to 1000 characters.
- **`carrier.Recipient` and `carrier.Parcel`** records were created here (the entity uses them); they are part of the carrier port vocabulary of task 07.
- **Repository:** `findByOrderId`, `findByTtn`, `findAllByOrderIdIn`, `findAllByStatus`.
- **Verified (2026-10-15):** `ShipmentTest` (44 cases, every allowed and forbidden transition) and `ShipmentRepositoryTest` (Testcontainers PostgreSQL: schema validate, unique `order_id` and `ttn`, tombstone constraint, version increment, DB checks) pass.
