---
status: TODO
service: order-service
---
# EP-ORD-JOB-01: Payment Window Expiry

## Context
An order holds reserved stock only while it waits for payment. When the payment window ends, the job releases the reservation and marks the order `EXPIRED`, so the stock becomes available to other customers. The job must never expire an order whose payment is still running: a charge can take several seconds, so the job waits for a grace period after the window.
*References:* [Order Spec — EP-ORD-JOB-01, EP-ORD-03](../../design/services-specs/order-service-spec.md), [Inventory EP-INV-07](../../design/services-specs/inventory-service-spec.md), [System Scenarios (SCN-S06)](../../design/scenarios/system-scenarios.md)

## Acceptance Criteria
- [ ] Scheduled every minute (configurable): orders with `status = PENDING_PAYMENT` and `reservationExpiresAt + grace <= now`; `grace` (default 30 s) must be longer than `PAYMENT_READ_TIMEOUT` — checked at startup.
- [ ] Per order: Inventory release first; on failure log and keep the order for the next run. Then one transaction: check-and-set `PENDING_PAYMENT → EXPIRED` + `OrderCancelled { reason: "EXPIRED" }` in the outbox; if the check-and-set fails (paid or cancelled meanwhile), skip.
- [ ] One failing order does not stop the run; replicas do not process one order twice at once (same choice as [task 12](12-finish-interrupted-cancellations-job.md)).
- [ ] Tests (injected clock): an order inside window + grace is untouched; after it → `EXPIRED`, release called once, `OrderCancelled` on the topic; release fails → stays `PENDING_PAYMENT`, retried next run; an order paid between selection and check-and-set is not expired.

## Technical Notes / Constraints
- `EXPIRED` does not go through `CANCELLATION_REQUESTED` and needs no refund.
- Releasing before the status change is deliberate: a release without the change is repeated harmlessly next run, an `EXPIRED` order with stock still reserved would never be cleaned up.
