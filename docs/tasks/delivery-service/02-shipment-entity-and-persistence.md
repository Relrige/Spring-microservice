---
status: TODO
service: delivery-service
---
# Shipment Entity and Persistence

## Context
`Shipment` is Delivery's only aggregate: one row per order, carrying the recipient snapshot from `OrderPaid`, the order items (needed to re-fetch packaging on a retry), the computed parcel, the TTN and the status. Shipments are never deleted. A `CANCELLED` row without recipient data (a *tombstone*) is how a cancellation that arrives before `OrderPaid` stops a shipment from ever being created.

The status rules live in the entity, so every later task (consumers, job, cancel, retry, carrier report) changes state through the same checked methods.
*References:* [Delivery Spec — Service Overview, Shipment lifecycle](../../design/services-specs/delivery-service-spec.md), [Domain Entities — Shipment](../../design/entities/entities.md)

## Acceptance Criteria
- [ ] Flyway `V1` creates `shipments` and `shipment_items`:
  - `shipments`: `id` (UUID PK), `order_id` (UUID, **unique**), `status`, `ttn` (unique when not null), `failure_reason`, `recipient_name`, `recipient_phone`, `recipient_address`, `carrier`, `total_weight_grams`, `length_cm`, `width_cm`, `height_cm`, `declared_value` (numeric), `currency`, `handed_over_at`, `attempts` (int, default 0), `next_attempt_at`, `version`, `created_at`, `updated_at`.
  - `shipment_items`: `shipment_id` (FK), `product_id`, `quantity`; primary key `(shipment_id, product_id)`.
- [ ] Database guards: `CHECK` on the status values; `quantity >= 1`; parcel values null or `> 0`; `declared_value >= 0`; recipient, carrier, declared value and currency are required unless `status = 'CANCELLED'` (tombstone).
- [ ] Partial index for the TTN job: due `TTN_PENDING` rows by `next_attempt_at`.
- [ ] `Shipment` entity with `@Version` and `ShipmentItem` as an `@ElementCollection` (or a child entity; document the choice). `ShipmentStatus` enum.
- [ ] Factory methods `Shipment.pending(...)` (from `OrderPaid`) and `Shipment.cancelledTombstone(orderId)`.
- [ ] Transition methods enforce the [lifecycle](../../design/services-specs/delivery-service-spec.md): `markGenerated(ttn, parcel)`, `recordFailedAttempt(reason, nextAttemptAt)`, `markFailed(reason)`, `cancel()`, `markInTransit(handedOverAt)`, `markDelivered()` (only from `IN_TRANSIT`). A forbidden transition throws a domain exception (mapped to `409` in task 03).
- [ ] `ShipmentRepository`: `findByOrderId`, `findByTtn`, `findAllByOrderIdIn`, `findAllByStatus` (for the `IN_TRANSIT` restart recovery). Race-safe creation and the job's claim query are added by tasks 10 and 11.
- [ ] Tests: unit tests for every allowed and forbidden transition; a Testcontainers repository test proves the schema matches the entity (`validate`), the unique `order_id`, the tombstone constraint, and that the version increases on update.

## Technical Notes / Constraints
- `orderId` is the natural key used by every caller; `id` stays as a surrogate key so the entity does not depend on Order's identifiers.
- Do not store packaging per item. Only the computed parcel is kept; packaging is always re-fetched (it may have been fixed by a worker).
- Store timestamps as `timestamptz` (`Instant`).
