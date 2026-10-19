---
status: DONE
service: notification-service
---
# Notification Log and Recipient Read Model: Schema and Persistence

## Context
Notification owns no domain entities, but it has two technical tables:
- **`notifications`**: one row per email to send. `event_id` is unique and works as the Idempotent Consumer's dedup key. The row keeps the rendered message and the sending state. The sender job (task 09) works only on this table.
- **`order_recipients`**: the read model `orderId → customerEmail`, filled from the order events (D1). It is needed because Delivery's `OrderDelivered` has no email.

This task creates the schema and the repository operations that the listeners and jobs need. They are tested directly against PostgreSQL, so the later tasks can rely on them.
*References:* [Notification Spec — Recipient read model, Notification log](../../design/services-specs/notification-service-spec.md), [Domain Entities — Notification](../../design/entities/entities.md)

## Acceptance Criteria
- [x] Flyway `V1__notifications.sql` creates both tables exactly as in the spec (column types, `NOT NULL`, `UNIQUE (event_id)`), plus an index on `notifications (status, next_attempt_at)`.
- [x] Enums `NotificationType { ORDER_PLACED, PAYMENT_RECEIVED, ORDER_SHIPPED, ORDER_DELIVERED, ORDER_CANCELLED, ORDER_EXPIRED }` and `NotificationStatus { PENDING, SENT, FAILED }`, stored as strings.
- [x] `NotificationEntity` with factory `pending(eventId, orderId, type, recipient, subject, body, now)`, where `recipient` may be null, and state changes `markSent(now)`, `recordFailure(reason, nextAttemptAt)`, `markFailed(reason)`. Invalid transitions (e.g. `markSent` on `FAILED`) throw.
- [x] Repository operations:
  - `insertIfAbsent(notification)` → `true` when inserted, `false` when the `event_id` already exists (`INSERT … ON CONFLICT (event_id) DO NOTHING`, no exception and no broken Hibernate session);
  - `upsertRecipient(orderId, email, now)` (`ON CONFLICT (order_id) DO UPDATE SET customer_email, updated_at`);
  - `findRecipient(orderId)`;
  - `claimDue(now, batchSize, lease)` → the claimed rows, oldest `next_attempt_at` first, `FOR UPDATE SKIP LOCKED`, with `next_attempt_at` moved forward by the lease in the same transaction;
  - `deleteOlderThan(cutoff)` for `SENT` / `FAILED` notifications and for `order_recipients` by `updated_at`.
- [x] Repository tests (Testcontainers PostgreSQL): the same `event_id` twice → one row, second call `false`; upsert twice → one row with the newer `updated_at`; `claimDue` skips rows in the future and rows that are `SENT` or `FAILED`; two concurrent claims never return the same row; the cleanup keeps `PENDING` rows and recent rows.

## Technical Notes / Constraints
- No JPA relationship between the tables: `order_id` is a plain UUID, as in every cross-aggregate reference in the project.
- Avoid Lombok `@Data` on entities; follow the entity convention of Customer and Delivery.
- Use an injected `Clock` for every `now` so later tests can move time.
- Compare with Delivery's `shipments` claim (Delivery task 11): the lease is a plain update, and there is no `version` column here, because only the job changes a row after it is inserted.

## Implementation Notes (2026-10-18)
- **Schema** `V1__create_notification_tables.sql`: both tables as in the spec, plus `CHECK` constraints on `type`, `status` and `attempts`, and `notifications_sent_has_recipient` (a `SENT` row always has a recipient and `sent_at`). Index `idx_notifications_status_next_attempt_at`.
- **Entities:** `NotificationEntity` (ID assigned in the factory, because the row is inserted with plain SQL; no `@Version`, see the class Javadoc) with `pending(…)`, `lease`, `assignRecipient`, `markSent`, `recordFailure`, `markFailed`. Both failure methods count the attempt, so a row given up after the fifth failure has `attempts = 5`. Any change from `SENT` / `FAILED` throws `IllegalStateException`; failure reasons are cut to 1000 characters. `OrderRecipient` is mapped only so Hibernate validates the table and JPQL can read it.
- **Repositories:**
  - `NotificationRepository` + fragment `NotificationRepositoryCustom(Impl)`:
    - `insertIfAbsent` runs `INSERT … ON CONFLICT (event_id) DO NOTHING` through `JdbcClient`, so a duplicate raises no exception and leaves no broken session. It joins the caller's JPA transaction.
    - `claimDue` is one `@Transactional` method: a native `SELECT … FOR UPDATE SKIP LOCKED`, then `lease()` on each row, written by dirty checking at commit.
    - `deleteOlderThan` deletes everything that is not `PENDING`.
    - `countByStatus` is for the gauge (task 11).
  - `OrderRecipientRepository`: `upsertRecipient` (native `ON CONFLICT (order_id) DO UPDATE`), `findRecipient`, `deleteOlderThan`.
- **Deviation from the AC wording:** `deleteOlderThan(cutoff)` exists once per repository instead of as one method covering both tables. The cleanup job (task 10) calls both.
- **Verified (2026-10-18):**
  - `NotificationEntityTest`: transitions and invalid transitions.
  - `NotificationPersistenceTest` (`@DataJpaTest` on Testcontainers PostgreSQL, no test transaction):
    - every column round-trips, including a null recipient;
    - a duplicate `event_id` gives one row and `false`;
    - an upsert run twice gives one row with the newer `updated_at`;
    - the claim works oldest first, respects the batch size and leases rows;
    - future, `SENT` and `FAILED` rows are not claimed;
    - 4 concurrent claims over 20 rows return 20 distinct rows;
    - cleanup keeps `PENDING` and recent rows.
