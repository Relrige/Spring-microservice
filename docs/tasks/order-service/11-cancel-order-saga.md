---
status: TODO
service: order-service
---
# EP-ORD-06: Cancel Order (Saga)

## Context
A customer may cancel their own order until it is handed to the courier. Cancelling undoes what the earlier steps did in other services: release the stock, cancel the shipment, refund the payment. Order orchestrates these steps synchronously; each of them is idempotent in the other service, so repeating one is always safe. If a step fails, the order stays `CANCELLATION_REQUESTED` and a job finishes it later (decision D3, [task 12](12-finish-interrupted-cancellations-job.md)).
*References:* [Order Spec — EP-ORD-06, Decisions](../../design/services-specs/order-service-spec.md), [Order State Machine — Cancellation Scope](../../design/entities/order-state-machine.md), [Inventory EP-INV-07](../../design/services-specs/inventory-service-spec.md), [Delivery EP-DEL-02](../../design/services-specs/delivery-service-spec.md), [Payment EP-PAY-02](../../design/services-specs/payment-service-spec.md), [System Scenarios (SCN-S07)](../../design/scenarios/system-scenarios.md)

## Acceptance Criteria
- [ ] `DELETE /orders/{orderId}`; role `CUSTOMER`, owner only (`404` otherwise).
- [ ] `CANCELLATION_REQUESTED` → `409` "already in progress"; status outside `{ PENDING_PAYMENT, PAID, PENDING_SHIPPING }` → `409`.
- [ ] Check-and-set to `CANCELLATION_REQUESTED` with `statusBeforeCancellation`, committed **before** the first remote call.
- [ ] Saga steps in order: release (always) → Delivery cancel (unless cancelled from `PENDING_PAYMENT`) → refund (only when `statusBeforeCancellation` is `PAID` or `PENDING_SHIPPING`; `409` "already refunded" counts as done) → one transaction `CANCELLED` + `OrderCancelled { reason: "CUSTOMER_CANCELLED" }` in the outbox.
- [ ] `202 { orderId, status }`: `CANCELLED` when all steps succeeded; `CANCELLATION_REQUESTED` when a step failed (the failed step and its error are stored, `cancellationAttempts` incremented; nothing is rolled back).
- [ ] The saga logic is one service method reused by the job of task 12.
- [ ] Tests: each start status gets exactly the right steps (the table of the state machine); a failing step leaves `CANCELLATION_REQUESTED` with the failure stored; a repeated saga after a partial success finishes without double effects (stubs count calls); a concurrent `TTNGenerated` does not move the order back to `PENDING_SHIPPING`; an integration test with WireMock for the three services and `OrderCancelled` on the topic.

## Technical Notes / Constraints
- Do not hold a database transaction across the remote calls.
- Payment's refund rejection (`502` with `reason: refund_rejected`) is a failed step like any other; the job and the manual-attention flag handle it.
