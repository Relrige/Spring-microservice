---
status: DONE
service: order-service
---
# EP-ORD-JOB-01: Payment Window Expiry

## Context
An order holds reserved stock only while it waits for payment. When the payment window ends, the job releases the reservation and marks the order `EXPIRED`, so the stock becomes available to other customers. The job must never expire an order whose payment is still running: a charge can take several seconds, so the job waits for a grace period after the window.
*References:* [Order Spec — EP-ORD-JOB-01, EP-ORD-03](../../design/services-specs/order-service-spec.md), [Inventory EP-INV-07](../../design/services-specs/inventory-service-spec.md), [System Scenarios (SCN-S06)](../../design/scenarios/system-scenarios.md)

## Acceptance Criteria
- [x] Scheduled every minute (configurable): orders with `status = PENDING_PAYMENT` and `reservationExpiresAt + grace <= now`; `grace` (default 30 s) must be longer than `PAYMENT_READ_TIMEOUT` — checked at startup.
- [x] Per order: Inventory release first; on failure log and keep the order for the next run. Then one transaction: check-and-set `PENDING_PAYMENT → EXPIRED` + `OrderCancelled { reason: "EXPIRED" }` in the outbox; if the check-and-set fails (paid or cancelled meanwhile), skip.
- [x] One failing order does not stop the run; replicas do not process one order twice at once (same choice as [task 12](12-finish-interrupted-cancellations-job.md)).
- [x] Tests (injected clock): an order inside window + grace is untouched; after it → `EXPIRED`, release called once, `OrderCancelled` on the topic; release fails → stays `PENDING_PAYMENT`, retried next run; an order paid between selection and check-and-set is not expired.

## Technical Notes / Constraints
- `EXPIRED` does not go through `CANCELLATION_REQUESTED` and needs no refund.
- Releasing before the status change is deliberate: a release without the change is repeated harmlessly next run, an `EXPIRED` order with stock still reserved would never be cleaned up.

## Implementation
Done on 2026-10-17:
- `jobs/PaymentWindowExpiryJob.expireDueOrders()`, `@Scheduled` every `order.expiry.interval` (`ORDER_EXPIRY_INTERVAL`, default `1m`): `OrderRepository.findExpired(now - grace, batch-size)` (task 02's query, oldest window first), then per order the Inventory release and, in one short transaction, a re-read check-and-set `PENDING_PAYMENT → EXPIRED` with `OrderCancelled { reason: EXPIRED }` in the outbox. A failed release logs a warning and leaves the order for the next run; an order paid or cancelled meanwhile (re-read status, or a version conflict at the flush) is skipped; an unexpected exception for one order is logged and the run goes on.
- `config/ExpiryProperties` (`order.expiry.interval`, `grace` from `ORDER_EXPIRY_GRACE` default `30s`, `batch-size` 100). **The service does not start** when the grace is not longer than `clients.payment.read-timeout` (8 s by default).
- Tests: `PaymentWindowExpiryJobTest` (6: the startup check; the query uses `now - grace`; release then `EXPIRED` with `OrderCancelled(EXPIRED)`; failed release keeps the order pending without an event; an order paid between selection and check-and-set is not expired; one failing order does not stop the run), `integration/FulfillmentAndExpiryIntegrationTest` (2 on PostgreSQL, Kafka and WireMock: of three orders, only the one past window plus grace is released (exactly one call) and expired, with `OrderCancelled { reason: EXPIRED }` on the topic; Inventory down → still `PENDING_PAYMENT`, the next run expires it).

## Decisions
Taken on 2026-10-17 (the criterion asked for the same choice as task 12):
- **No claim per order here.** Unlike the cancellation saga, a second processing of the same order is harmless by itself: the second release does nothing (idempotent in Inventory), and the second check-and-set finds the order already `EXPIRED`. Claiming would only add a write per order.
- **The window is checked again inside the transaction** (`reservationExpiresAt <= now - grace`), so a row read just before a change is never expired on stale data.
