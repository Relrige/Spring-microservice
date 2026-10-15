---
status: DONE
service: payment-service
---
# EP-PAY-02: Refund Order (Internal)

## Context
Order Service calls this endpoint during the cancellation saga when the cancelled order was already paid (`PAID` or `PENDING_SHIPPING`). Payment Service finds the successful charge, asks the gateway to refund it and records a separate `REFUND` row. Like the charge, the refund must never happen twice; unlike the charge, it is a **compensating action** in a saga, so Order Service will retry it until it succeeds or a human steps in.
*References:* [Payment Spec — EP-PAY-02](../../design/services-specs/payment-service-spec.md), [Order Spec — EP-ORD-05 (cancellation saga)](../../design/services-specs/order-service-spec.md), [System Scenarios (SCN-S07)](../../design/scenarios/system-scenarios.md), [Charge task](06-charge-card.md)

## Acceptance Criteria
- [x] `POST /payments/refund` accepts `{ orderId, amount, currency }`; validation as in [task 06](06-charge-card.md) → `400`.
- [x] No `SUCCESS` `CHARGE` for the order → `422` ("No successful charge found for this order — cannot refund"); the gateway is not called.
- [x] A `SUCCESS` `REFUND` already exists for the order → `409`; the gateway is not called.
- [x] `amount` / `currency` differ from the original charge → `422` ("only full refunds are supported"); see *Decisions*.
- [x] **Approved:** persist `REFUND/SUCCESS` with the gateway's refund ID; return `200 { status: "SUCCESS" }`.
- [x] **Declined / unavailable / timeout:** persist `REFUND/FAILED` with the reason; return `502` / `504`.
- [x] Every refund is sent with the idempotency key `"refund:" + orderId` (one full refund per order, so one key). Concurrent or repeated refunds are refunded once by the gateway; the second request answers `409` (same handling of the unique-index violation as in [task 06](06-charge-card.md)).
- [x] Tests: service unit tests for each outcome; controller slice tests; an integration test of the full path *charge `4242` → refund → second refund `409`*, and refund without a charge `422`.

## Technical Notes / Constraints
- The refund uses the charge's `gatewayTransactionId`; the caller never sends it (Order Service does not know gateway IDs, that is the point of the ACL).
- A `409` on a retried refund means the first attempt succeeded but its answer was lost. Order Service should treat `409` for its own `orderId` as "already refunded" and continue the saga.

## Decisions
Agreed on 2026-10-15:
- **Only full refunds.** An order is cancelled as a whole, so a refund always returns the whole charge. `amount` stays in the request as a consistency check against the charge (mismatch → `422`), so a bug in Order Service cannot refund a wrong sum silently. Partial refunds would turn "already refunded" into "refunded total + amount > charged" and are out of scope.
- **Idempotency key per order** (`"refund:" + orderId`), following the charge decision in [task 06](06-charge-card.md). Because there is only one full refund per order, no attempt counter is needed.

## Questions to Discuss
- The spec answers a refund decline with `502`, the same as "gateway down". Is a declined refund really retryable? What should the saga do after N failed attempts (alert, `CANCELLATION_REQUESTED` stays, manual resolution)? This answer belongs in the Order saga task as well.

## Implementation
Done on 2026-10-15:
- `dto/RefundRequest`; `services/PaymentService.refund`: charge lookup (`422`), already refunded (`409`), full-amount check with `compareTo` so `1499.9` equals `1499.90` (`RefundAmountMismatchException` → `422`), gateway refund with the charge's `gatewayTransactionId` and key `refund:<orderId>`; `Approved` saves `REFUND/SUCCESS` (index violation → `409`), `Declined` saves `REFUND_REJECTED` and answers `502 { reason: "refund_rejected" }` (`RefundRejectedException`), timeout / unavailable as in the charge.
- The response is `200 { transactionId, status: "SUCCESS" }` like the charge (the spec said `{ status }` only; the extra ID is harmless and keeps one response type).
- Tests: `PaymentServiceTest$Refund` (8), `PaymentControllerTest` (refund part: success, invalid body, 422, 422, 409, 502), `RefundIntegrationTest` (5: charge → refund → second refund `409` with one refund at the mock, refund without charge `422`, refund after a declined charge only `422`, partial `422` then full `200`, 10 concurrent refunds → one success, one refund at the mock).
- Spec EP-PAY-02 and `api-contracts.md` updated.

## Still open
- The question above about declined refunds remains for the Order saga task. Note for it: the refund key is fixed per order, so if a provider ever stored a *rejection* for that key, repeating the refund would replay the rejection forever; the saga then needs manual resolution. The mock only rejects refunds of charges it does not know, which Payment Service never asks for.
