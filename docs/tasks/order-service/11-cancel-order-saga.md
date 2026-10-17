---
status: DONE
service: order-service
---
# EP-ORD-06: Cancel Order (Saga)

## Context
A customer may cancel their own order until it is handed to the courier. Cancelling undoes what the earlier steps did in other services: release the stock, cancel the shipment, refund the payment. Order orchestrates these steps synchronously; each of them is idempotent in the other service, so repeating one is always safe. If a step fails, the order stays `CANCELLATION_REQUESTED` and a job finishes it later (decision D3, [task 12](12-finish-interrupted-cancellations-job.md)).
*References:* [Order Spec — EP-ORD-06, Decisions](../../design/services-specs/order-service-spec.md), [Order State Machine — Cancellation Scope](../../design/entities/order-state-machine.md), [Inventory EP-INV-07](../../design/services-specs/inventory-service-spec.md), [Delivery EP-DEL-02](../../design/services-specs/delivery-service-spec.md), [Payment EP-PAY-02](../../design/services-specs/payment-service-spec.md), [System Scenarios (SCN-S07)](../../design/scenarios/system-scenarios.md)

## Acceptance Criteria
- [x] `DELETE /orders/{orderId}`; role `CUSTOMER`, owner only (`404` otherwise).
- [x] `CANCELLATION_REQUESTED` → `409` "already in progress"; status outside `{ PENDING_PAYMENT, PAID, PENDING_SHIPPING }` → `409`.
- [x] Check-and-set to `CANCELLATION_REQUESTED` with `statusBeforeCancellation`, committed **before** the first remote call.
- [x] Saga steps in order: release (always) → Delivery cancel (unless cancelled from `PENDING_PAYMENT`) → refund (only when `statusBeforeCancellation` is `PAID` or `PENDING_SHIPPING`; `409` "already refunded" counts as done) → one transaction `CANCELLED` + `OrderCancelled { reason: "CUSTOMER_CANCELLED" }` in the outbox.
- [x] `202 { orderId, status }`: `CANCELLED` when all steps succeeded; `CANCELLATION_REQUESTED` when a step failed (the failed step and its error are stored, `cancellationAttempts` incremented; nothing is rolled back).
- [x] The saga logic is one service method reused by the job of task 12.
- [x] Tests: each start status gets exactly the right steps (the table of the state machine); a failing step leaves `CANCELLATION_REQUESTED` with the failure stored; a repeated saga after a partial success finishes without double effects (stubs count calls); a concurrent `TTNGenerated` does not move the order back to `PENDING_SHIPPING`; an integration test with WireMock for the three services and `OrderCancelled` on the topic.

## Technical Notes / Constraints
- Do not hold a database transaction across the remote calls.
- Payment's refund rejection (`502` with `reason: refund_rejected`) is a failed step like any other; the job and the manual-attention flag handle it.

## Implementation
Done on 2026-10-17:
- `controllers/OrderController` `DELETE /orders/{orderId}` → `OrderCancellationService.cancel` → `202 dto/CancellationResponse { orderId, status }`.
- `services/OrderCancellationService`: `OrderAccess.loadOwned` (`404`); `CANCELLATION_REQUESTED` → `CancellationInProgressException` (`409` "already in progress", a subclass of `InvalidOrderStateException`, so the body carries `orderStatus`); outside `CANCELLABLE` → `409`. Then the **check-and-set in its own committed transaction** before any remote call: re-read, check again, `requestCancellation(now)`, `saveAndFlush`. A payment committed in between fails the version check; the order is re-read (at most three times) and the cancellation starts from `PAID`, so the refund is owed.
- `services/CancellationSaga.run(orderId)` (also used by the job of task 12), without a transaction during the calls: release → Delivery cancel (unless started from `PENDING_PAYMENT`) → refund (only if `refundOwed()`; `AlreadyRefunded` counts as done). The first failing step stops the run; any exception of a step counts as its failure. Then one short transaction: success → `markCancelled` + `OrderCancelled { reason: CUSTOMER_CANCELLED }`; failure → `recordCancellationFailure("<step>: <error>", now, 5)` (truncated to the column's 1000 characters), a warning, and **one** `ERROR` line when the order becomes flagged. An order no longer cancelling is left alone.
- `OrderEntity.requestCancellation(now)` now also sets `cancellationLastAttemptAt`: the first attempt starts with the request, so the job waits its back-off before touching a fresh cancellation.
- Tests: `CancellationSagaTest` (12: unpaid → release only; `PAID` and `PENDING_SHIPPING` → the three steps in order; `OrderCancelled` with the customer's reason; already refunded; failed release stops there with the failure stored; failed shipment cancel → no refund; rejected refund; a repeat after a partial success runs every step again and finishes with one event; the fifth failure flags the order and alerts once; an order no longer cancelling; a late `TTNGenerated` cannot move a cancelling order back), `OrderCancellationServiceTest` (10: stored before the saga, failed step still accepted, in progress → `409` without a saga, four final statuses → `409`, a second request that lost the race → `409`, a payment committed meanwhile → refund owed, endless conflicts give up), `OrderControllerTest` (`202`, `409` in progress, `409` shipped), `integration/CancellationIntegrationTest` (PostgreSQL, Kafka, WireMock for the three services: a paid order → all three calls, `CANCELLED`, `OrderCancelled` on the topic; an unpaid order → release only; repeated `DELETE` while pending → `409` and nothing started; shipped → `409` without calls; another customer's order → `404`, plus the job tests of task 12).

## Decisions
- **The first failing step stops the run** instead of trying the later steps: the steps keep the spec's order, and the next run repeats all of them anyway.
- **`CancellationInProgressException` extends `InvalidOrderStateException`**, so both `409`s have the same shape (`orderStatus`) and only the text differs.
