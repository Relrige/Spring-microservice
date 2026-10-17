---
status: TODO
service: order-service
---
# EP-ORD-03: Pay for an Order

## Context
The customer pays their own order inside the payment window. Order forwards the card to Payment Service, which charges it exactly once thanks to its idempotency keys, and turns Payment's answers into what the customer sees. The hard part is concurrency: the charge can take seconds, and during that time the customer may cancel or the window may expire.
*References:* [Order Spec — EP-ORD-03](../../design/services-specs/order-service-spec.md), [Payment Spec — EP-PAY-01, Card Data](../../design/services-specs/payment-service-spec.md), [Payment task 06 — notes for the Order client](../payment-service/06-charge-card.md), [Customer Scenarios (SCN-C16, SCN-C17)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [ ] `POST /orders/{orderId}/pay` with `{ cardDetails: { number, expiryMonth, expiryYear, cvv } }`; role `CUSTOMER`, owner only (`404` otherwise).
- [ ] Card validation as Payment's (`400` before any call); card data is never stored, logged or echoed.
- [ ] Not `PENDING_PAYMENT` → `409`; `reservationExpiresAt <= now` → `409` type `payment-window-expired`, no charge.
- [ ] Payment answers: `200` or `409` "already charged" → paid; `402` → `402 { reason }`, order unchanged, window not extended; `409` "attempt in progress" → `409` ("retry with the same card"); `502` / `504` / timeout → `503`, order unchanged.
- [ ] Paid: one transaction with the check-and-set `PENDING_PAYMENT → PAID`, `paidAt`, and the fat `OrderPaid` in the outbox; `200 { orderId, status: "PAID" }`.
- [ ] **Safety net:** if the check-and-set fails because the order changed during the charge (cancelled or expired), refund at once, log a warning, answer `409`.
- [ ] Tests: unit tests for every Payment answer; a test where the order is cancelled while the (stubbed) charge runs → refund called, `409`; an integration test with WireMock Payment: paid order and `OrderPaid` on the topic with every field Delivery needs; the card number appears in no log line.

## Technical Notes / Constraints
- No retry by Order: Payment already repeats uncertain calls with the same key, and a repeat by the customer with the same card is safe.
- Read timeout ≥ 6 s (`PAYMENT_READ_TIMEOUT`); the expiry job's grace period must be longer ([task 13](13-payment-window-expiry-job.md)).
