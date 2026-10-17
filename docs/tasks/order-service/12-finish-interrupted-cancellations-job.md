---
status: TODO
service: order-service
---
# EP-ORD-JOB-02: Finish Interrupted Cancellations

## Context
When a step of the cancellation saga fails, the order stays `CANCELLATION_REQUESTED` (decision D3). This job repeats the saga for such orders with back-off until it succeeds. Most failures are short outages, but some never heal on their own (a refund the provider keeps rejecting); after five failed attempts the order is flagged for a person, while the job keeps trying, because a refund owed to a customer must not be dropped.
*References:* [Order Spec — EP-ORD-JOB-02, Decisions](../../design/services-specs/order-service-spec.md), [Cancel task](11-cancel-order-saga.md), [Payment task 07 — still open](../payment-service/07-refund-order.md)

## Acceptance Criteria
- [ ] A scheduled job (every minute, configurable) picks orders in `CANCELLATION_REQUESTED` whose last attempt is older than the back-off (1 min, 5 min, 15 min, then every 30 min).
- [ ] It runs the saga method of task 11 for each; success → `CANCELLED` + `OrderCancelled`; failure → `cancellationAttempts + 1`, `cancellationFailure` = failed step and error, warning logged.
- [ ] After 5 failed attempts: `needsManualAttention = true` and one `ERROR` log line (the alert) per order; retries continue at the 30-minute pace.
- [ ] One order failing never stops the run; several replicas never process the same order at once (`FOR UPDATE SKIP LOCKED` claim, or the job on one replica — decide).
- [ ] Tests: a stubbed step fails twice then succeeds → `CANCELLED` after the third attempt; five failures set the flag and log once; the back-off is respected (a clock is injected); a race with a customer's repeated `DELETE` gives `409` to the customer and one finished saga.

## Technical Notes / Constraints
- Every saga step is idempotent in the other service; that is what makes repeating the whole saga safe.
- Resolving a flagged order (e.g. a manual refund at the provider) is outside the application; the flag and the log line are how it reaches a person.
