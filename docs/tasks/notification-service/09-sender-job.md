---
status: DONE
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
- [x] Settings in `application.yml`, bound to a properties record: `notification.sender.enabled` (default `true`), `interval` 2 s, `batch-size` 20, `lease` 2 min, `backoff` 30 s, 2 min, 10 min, 30 min.
- [x] The claim uses `claimDue` (task 02) and commits before any SMTP call.
- [x] Per claimed row, outside a transaction:
  - `recipient` null → look it up in `order_recipients`; found → use it (and store it with the result); not found → retryable failure `recipient_unknown`;
  - then `EmailSender.send`.
- [x] Results applied in a new transaction:
  - sent → `SENT`, `sent_at`;
  - retryable → `attempts + 1`, `failure_reason`, `next_attempt_at = now + backoff[attempts - 1]`; after the fifth failed attempt → `FAILED`, one `ERROR` log line;
  - not retryable → `FAILED` at once, `WARN`.
  One failing row never stops the run.
- [x] Logging names the notification `id`, `orderId`, `type`, attempt and reason, never the address.
- [x] Tests (PostgreSQL + Mailpit, or a fake `EmailSender` where noted, and a controllable `Clock`):
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

## Implementation Notes (2026-10-18)
- **`jobs/NotificationSenderJob`**: `@Scheduled(fixedDelay = initialDelay = notification.sender.interval)`, switched off with `notification.sender.enabled=false`. The initial delay keeps a run from starting while the context is still starting, which also makes the tests deterministic. `run()` repeats `runOnce()` while full batches are claimed.
  1. **Claim:** `claimDue(now, batchSize, lease)` (task 02) in its own transaction, committed before any SMTP call.
  2. **Attempt** (`SendOutcome`), outside any transaction:
     - a null recipient is looked up with `findRecipient(orderId)`;
     - not found → retryable `recipient_unknown`;
     - otherwise `EmailSender.send(...)`, whose `EmailSendException` gives the reason and retryability.
  3. **Apply** (`TransactionTemplate`, a new transaction):
     - reload the row; if it is no longer `PENDING` (another instance finished it after our lease expired), the result is discarded with a `WARN`;
     - store a just-resolved recipient;
     - sent → `markSent`;
     - retryable with back-offs left → `recordFailure(reason, now + backoff[attempts])`;
     - retryable with none left → `markFailed`, `ERROR` "giving up after 5 attempts";
     - permanent → `markFailed`, `WARN`.
  - An unexpected exception for one row is logged and the run goes on; the lease then expires and the attempt is redone.
- **Settings** (`SenderJobProperties`, validated): `enabled: true`, `interval: 2s`, `batch-size: 20`, `lease: 2m`, `backoff: 30s, 2m, 10m, 30m`. With 4 back-offs the 5th failure ends in `FAILED` with `attempts = 5`. `spring.task.scheduling.pool.size: 2` is shared with the cleanup job.
- **Logging:** notification ID, order, type, attempt, reason and next attempt; never the address or the body. Checked: no address appears in the test log.
- **Resilience4j `Retry` / Spring Retry vs. this job:** they repeat one call within a request, seconds apart, in memory. These back-offs are up to 30 minutes, must survive restarts, and must not block a Kafka partition, so the state lives in `attempts` / `next_attempt_at`. Also in the class Javadoc.
- **Verified (2026-10-18):** `NotificationSenderJobIntegrationTest` (PostgreSQL + Kafka + **real Mailpit**, `MutableClock`, scheduled run pushed to 1 h, `EmailSender` spied over the SMTP adapter):
  - happy path → the email is in Mailpit with subject and body, and the row is `SENT`;
  - two failures, then success → `next_attempt_at` +30 s, then +2 m; not picked before it is due; `SENT` on attempt 3;
  - always failing → `FAILED` after 5 attempts with the last reason, and never picked again;
  - `recipient_rejected` → `FAILED` after one attempt;
  - an `ORDER_DELIVERED` row without a known recipient → `recipient_unknown`, no SMTP call; after the upsert it is sent and the recipient is stored;
  - a row due in the future is not picked;
  - two job instances at once on 10 rows → 10 sends, 10 emails;
  - a simulated stop after the send (storing the result fails) → leased, then sent again after the lease. Mailpit shows the email twice: the documented at-least-once window;
  - one failing row does not stop the run.
