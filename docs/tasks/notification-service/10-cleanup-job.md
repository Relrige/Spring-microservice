---
status: TODO
service: notification-service
---
# EP-NOTIF-JOB-02: Clean Up Old Notifications and Recipients

## Context
Both technical tables hold personal data: email addresses and email bodies with order details. They must not grow forever. A cleanup job deletes finished notifications and stale recipients after `NOTIFICATION_RETENTION` (default 30 days).

Deleting a notification also deletes its dedup key. That is acceptable: Kafka and the producers' outboxes redeliver within minutes, not weeks. Inventory's `processed_events` retention (30 days) makes the same trade-off.
*References:* [Notification Spec — EP-NOTIF-JOB-02, Notification log](../../design/services-specs/notification-service-spec.md), [Catalog task 23 — key retention](../catalog-service/23-retryable-topic-idempotent-consumer-and-report.md)

## Acceptance Criteria
- [ ] `NOTIFICATION_RETENTION` (bound to `notification.retention`, default `30d`) and `notification.cleanup.interval` (default `1h`).
- [ ] The job deletes `SENT` and `FAILED` notifications with `created_at` older than the cutoff, and `order_recipients` with `updated_at` older than the cutoff. `PENDING` rows are never deleted.
- [ ] Logs the deleted counts at `INFO` when at least one row was deleted.
- [ ] Test (PostgreSQL, controllable `Clock`): old `SENT` / `FAILED` rows and old recipients are deleted; recent rows and every `PENDING` row are kept.

## Technical Notes / Constraints
- Several replicas need no lock: deleting the same rows twice is harmless.
- A pending `ORDER_DELIVERED` row whose recipient was cleaned up would end `FAILED` with `recipient_unknown`. With a 30-day retention and deliveries within days this does not happen; mention it in the notes.
