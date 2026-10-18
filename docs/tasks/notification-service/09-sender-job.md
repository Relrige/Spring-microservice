---
status: TODO
service: notification-service
---
# EP-NOTIF-JOB-01: Send Pending Notifications

## Context
This is the core of the service. A scheduled job turns `PENDING` rows into sent emails. It retries failures with a back-off stored in the database, so retries survive restarts and never block a Kafka partition (D2). It also resolves the missing recipient of `ORDER_DELIVERED` rows from the read model (D1).

The job has the same shape as Delivery's TTN job:
1. claim rows with a lease and commit;
2. call SMTP outside any transaction;
3. apply the result in a new transaction.

Sending is **at-least-once**. A stop after the SMTP server accepted a message, but before the row is marked `SENT`, sends it again when the lease expires. SMTP has no idempotency key, so this small window is accepted and documented.
*References:* [Notification Spec — EP-NOTIF-JOB-01](../../design/services-specs/notification-service-spec.md), [Delivery TTN generation job task](../delivery-service/11-ttn-generation-job.md)

## Acceptance Criteria
- [ ] Settings in `application.yml`, bound to a properties record: `notification.sender.enabled` (default `true`), `interval` 2 s, `batch-size` 20, `lease` 2 min, `backoff` 30 s, 2 min, 10 min, 30 min.
- [ ] The claim uses `claimDue` (task 02) and commits before any SMTP call.
- [ ] Per claimed row, outside a transaction:
  - `recipient` null → look it up in `order_recipients`; found → use it (and store it with the result); not found → retryable failure `recipient_unknown`;
  - then `EmailSender.send`.
- [ ] Results applied in a new transaction:
  - sent → `SENT`, `sent_at`;
  - retryable → `attempts + 1`, `failure_reason`, `next_attempt_at = now + backoff[attempts - 1]`; after the fifth failed attempt → `FAILED`, one `ERROR` log line;
  - not retryable → `FAILED` at once, `WARN`.
  One failing row never stops the run.
- [ ] Logging names the notification `id`, `orderId`, `type`, attempt and reason, never the address.
- [ ] Tests (PostgreSQL + Mailpit, or a fake `EmailSender` where noted, and a controllable `Clock`):
  - happy path → the email is in Mailpit and the row is `SENT`;
  - fake sender fails twice, then succeeds → `SENT` on the third attempt, with the expected `next_attempt_at` values;
  - always failing → `FAILED` after 5 attempts with the last reason;
  - `recipient_rejected` → `FAILED` after one attempt;
  - an `ORDER_DELIVERED` row without a known recipient → retried, and sent once the recipient is upserted;
  - rows in the future are not picked;
  - two job instances at once → each email sent once;
  - an expired lease (simulated stop after send) → sent again (documents the at-least-once window).

## Technical Notes / Constraints
- Never hold a transaction or a row lock during the SMTP call or the recipient lookup.
- Spring's task scheduler is shared with the cleanup job (task 10); set its pool size explicitly (e.g. 2), as Delivery does.
- Compare with Resilience4j `Retry` and with Spring Retry in the implementation notes. They suit short retries inside one call, but not retries 30 minutes apart that must survive a restart.
