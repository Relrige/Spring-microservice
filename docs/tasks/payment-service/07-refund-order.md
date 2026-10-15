---
status: TODO
service: payment-service
---
# EP-PAY-02: Refund Order (Internal)

## Context
Order Service calls this endpoint during the cancellation saga when the cancelled order was already paid (`PAID` or `PENDING_SHIPPING`). Payment Service finds the successful charge, asks the gateway to refund it and records a separate `REFUND` row. Like the charge, the refund must never happen twice; unlike the charge, it is a **compensating action** in a saga, so Order Service will retry it until it succeeds or a human steps in.
*References:* [Payment Spec — EP-PAY-02](../../design/services-specs/payment-service-spec.md), [Order Spec — EP-ORD-05 (cancellation saga)](../../design/services-specs/order-service-spec.md), [System Scenarios (SCN-S07)](../../design/scenarios/system-scenarios.md), [Charge task](06-charge-card.md)

## Acceptance Criteria
- [ ] `POST /payments/refund` accepts `{ orderId, amount, currency }`; validation as in [task 06](06-charge-card.md) → `400`.
- [ ] No `SUCCESS` `CHARGE` for the order → `422` ("No successful charge found for this order — cannot refund"); the gateway is not called.
- [ ] A `SUCCESS` `REFUND` already exists for the order → `409`; the gateway is not called.
- [ ] `amount` / `currency` differ from the original charge → `422` ("only full refunds are supported"); see *Decisions*.
- [ ] **Approved:** persist `REFUND/SUCCESS` with the gateway's refund ID; return `200 { status: "SUCCESS" }`.
- [ ] **Declined / unavailable / timeout:** persist `REFUND/FAILED` with the reason; return `502` / `504`.
- [ ] Every refund is sent with the idempotency key `"refund:" + orderId` (one full refund per order, so one key). Concurrent or repeated refunds are refunded once by the gateway; the second request answers `409` (same handling of the unique-index violation as in [task 06](06-charge-card.md)).
- [ ] Tests: service unit tests for each outcome; controller slice tests; an integration test of the full path *charge `4242` → refund → second refund `409`*, and refund without a charge `422`.

## Technical Notes / Constraints
- The refund uses the charge's `gatewayTransactionId`; the caller never sends it (Order Service does not know gateway IDs, that is the point of the ACL).
- A `409` on a retried refund means the first attempt succeeded but its answer was lost. Order Service should treat `409` for its own `orderId` as "already refunded" and continue the saga.

## Decisions
Agreed on 2026-10-15:
- **Only full refunds.** An order is cancelled as a whole, so a refund always returns the whole charge. `amount` stays in the request as a consistency check against the charge (mismatch → `422`), so a bug in Order Service cannot refund a wrong sum silently. Partial refunds would turn "already refunded" into "refunded total + amount > charged" and are out of scope.
- **Idempotency key per order** (`"refund:" + orderId`), following the charge decision in [task 06](06-charge-card.md). Because there is only one full refund per order, no attempt counter is needed.

## Questions to Discuss
- The spec answers a refund decline with `502`, the same as "gateway down". Is a declined refund really retryable? What should the saga do after N failed attempts (alert, `CANCELLATION_REQUESTED` stays, manual resolution)? This answer belongs in the Order saga task as well.
