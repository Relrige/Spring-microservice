---
status: DONE
service: order-service
---
# EP-ORD-JOB-02: Finish Interrupted Cancellations

## Context
When a step of the cancellation saga fails, the order stays `CANCELLATION_REQUESTED` (decision D3). This job repeats the saga for such orders with back-off until it succeeds. Most failures are short outages, but some never heal on their own (a refund the provider keeps rejecting); after five failed attempts the order is flagged for a person, while the job keeps trying, because a refund owed to a customer must not be dropped.
*References:* [Order Spec — EP-ORD-JOB-02, Decisions](../../design/services-specs/order-service-spec.md), [Cancel task](11-cancel-order-saga.md), [Payment task 07 — still open](../payment-service/07-refund-order.md)

## Acceptance Criteria
- [x] A scheduled job (every minute, configurable) picks orders in `CANCELLATION_REQUESTED` whose last attempt is older than the back-off (1 min, 5 min, 15 min, then every 30 min).
- [x] It runs the saga method of task 11 for each; success → `CANCELLED` + `OrderCancelled`; failure → `cancellationAttempts + 1`, `cancellationFailure` = failed step and error, warning logged.
- [x] After 5 failed attempts: `needsManualAttention = true` and one `ERROR` log line (the alert) per order; retries continue at the 30-minute pace.
- [x] One order failing never stops the run; several replicas never process the same order at once (`FOR UPDATE SKIP LOCKED` claim, or the job on one replica — decide).
- [x] Tests: a stubbed step fails twice then succeeds → `CANCELLED` after the third attempt; five failures set the flag and log once; the back-off is respected (a clock is injected); a race with a customer's repeated `DELETE` gives `409` to the customer and one finished saga.

## Technical Notes / Constraints
- Every saga step is idempotent in the other service; that is what makes repeating the whole saga safe.
- Resolving a flagged order (e.g. a manual refund at the provider) is outside the application; the flag and the log line are how it reaches a person.

## Implementation
Done on 2026-10-17:
- `jobs/InterruptedCancellationsJob.retryDueCancellations()`, `@Scheduled` every `order.cancellation.retry-interval` (`ORDER_CANCELLATION_RETRY_INTERVAL`, default `1m`). It reads up to `batch-size` (100) orders in `CANCELLATION_REQUESTED`, the longest waiting first, skips those whose back-off has not passed, claims each due order and runs `CancellationSaga.run` (task 11). An unexpected exception for one order is logged and the run goes on.
- `config/CancellationProperties` (`order.cancellation.*`): `backoff: 1m, 5m, 15m, 30m` (after the request or the first failure 1 min, then 5, 15, and 30 min forever) and `manual-attention-after: 5`. The flag and the single `ERROR` line come from the saga; flagged orders keep being retried every 30 minutes.
- **Several replicas:** the claim is `OrderEntity.startCancellationAttempt(now)` saved in a short transaction. Two replicas that read the same version cannot both commit it; the loser skips the order. No lock is held during the remote calls, and the claim restarts the back-off, so the order is not due again while its attempt runs.
- Tests: `InterruptedCancellationsJobTest` (5: the back-off series; due only after the back-off for a fresh request, after the second failure and for a flagged order; due orders claimed and run, others skipped; an order claimed by another replica skipped; one failing order does not stop the run), `integration/CancellationIntegrationTest` (a refund rejected twice then accepted → `202 CANCELLATION_REQUESTED`, the job leaves it alone before the back-off, then attempt 2 fails and attempt 3 finishes it: three refund and release calls, one `OrderCancelled`; six failures → flagged, one alert line, still retried). The test properties set the interval to 1 h, so the job never runs in the background of other tests.

## Decisions
Taken on 2026-10-17 (the criterion asked to decide):
- **A claim per order with the version column, not `FOR UPDATE SKIP LOCKED` and not "only on one replica".** `SKIP LOCKED` would hold the row lock, and so a transaction, across the remote calls; an advisory lock like the outbox relay's would also hold a transaction for the whole run. The optimistic claim needs neither and is the same mechanism as every other status change of an order.
- **The back-off is checked in Java over the longest-waiting 100 orders**, not in SQL: there are few such orders. With more than 100 stuck orders, flagged ones waiting 30 minutes could hide newer ones for a run; a `next_attempt_at` column would fix that if it ever matters.

## Not done here
- Local test runs showed Docker Desktop's VM clock three hours ahead of Windows. The tests therefore never compare with the database's `now()`; restart the Docker VM (`wsl --shutdown`) if timestamps written by SQL look wrong locally.
