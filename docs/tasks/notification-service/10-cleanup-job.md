---
status: DONE
service: notification-service
---
# EP-NOTIF-JOB-02: Clean Up Old Notifications and Recipients

## Context
Both technical tables hold personal data: email addresses and email bodies with order details. They must not grow forever. A cleanup job deletes finished notifications and stale recipients after `NOTIFICATION_RETENTION` (default 30 days).

Deleting a notification also deletes its dedup key. That is acceptable: Kafka and the producers' outboxes redeliver within minutes, not weeks. Inventory's `processed_events` retention (30 days) makes the same trade-off.
*References:* [Notification Spec — EP-NOTIF-JOB-02, Notification log](../../design/services-specs/notification-service-spec.md), [Catalog task 23 — key retention](../catalog-service/23-retryable-topic-idempotent-consumer-and-report.md)

## Acceptance Criteria
- [x] `NOTIFICATION_RETENTION` (bound to `notification.retention`, default `30d`) and `notification.cleanup.interval` (default `1h`).
- [x] The job deletes `SENT` and `FAILED` notifications with `created_at` older than the cutoff, and `order_recipients` with `updated_at` older than the cutoff. `PENDING` rows are never deleted.
- [x] Logs the deleted counts at `INFO` when at least one row was deleted.
- [x] Test (PostgreSQL, controllable `Clock`): old `SENT` / `FAILED` rows and old recipients are deleted; recent rows and every `PENDING` row are kept.

## Technical Notes / Constraints
- Several replicas need no lock: deleting the same rows twice is harmless.
- A pending `ORDER_DELIVERED` row whose recipient was cleaned up would end `FAILED` with `recipient_unknown`. With a 30-day retention and deliveries within days this does not happen; mention it in the notes.

## Implementation Notes (2026-10-18)
- `jobs/CleanupJob`: `@Scheduled(fixedDelay = notification.cleanup.interval)`, default `1h`, sharing the scheduler pool of 2 with the sender job.
  - The cutoff is `clock.instant() - notification.retention`, bound to `NOTIFICATION_RETENTION`, default `30d` (Spring Boot's duration format).
  - It calls `NotificationRepository.deleteOlderThan` (everything not `PENDING`, by `created_at`) and `OrderRecipientRepository.deleteOlderThan` (by `updated_at`), both from task 02.
  - One `INFO` line gives the counts when something was deleted.
- No lock between replicas (deleting twice is harmless). The first run happens at startup, which does no harm.
- **Edge case documented in the class Javadoc:** a `PENDING` `ORDER_DELIVERED` row whose recipient was already cleaned up would end `FAILED` with `recipient_unknown`. With 30 days of retention and deliveries within days this does not happen.
- **Verified (2026-10-18):** `CleanupJobIntegrationTest` (Testcontainers, `MutableClock`, scheduled run pushed to 1 h):
  - old `SENT` / `FAILED` rows and an old recipient are deleted;
  - an old `PENDING` row, recent rows and a recent recipient are kept;
  - a `SENT` row survives at retention minus 1 minute and is deleted at retention plus 1 minute.
