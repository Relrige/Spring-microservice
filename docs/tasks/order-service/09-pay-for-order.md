---
status: DONE
service: order-service
---
# EP-ORD-03: Pay for an Order

## Context
The customer pays their own order inside the payment window. Order forwards the card to Payment Service, which charges it exactly once thanks to its idempotency keys, and turns Payment's answers into what the customer sees. The hard part is concurrency: the charge can take seconds, and during that time the customer may cancel or the window may expire.
*References:* [Order Spec — EP-ORD-03](../../design/services-specs/order-service-spec.md), [Payment Spec — EP-PAY-01, Card Data](../../design/services-specs/payment-service-spec.md), [Payment task 06 — notes for the Order client](../payment-service/06-charge-card.md), [Customer Scenarios (SCN-C16, SCN-C17)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [x] `POST /orders/{orderId}/pay` with `{ cardDetails: { number, expiryMonth, expiryYear, cvv } }`; role `CUSTOMER`, owner only (`404` otherwise).
- [x] Card validation as Payment's (`400` before any call); card data is never stored, logged or echoed.
- [x] Not `PENDING_PAYMENT` → `409`; `reservationExpiresAt <= now` → `409` type `payment-window-expired`, no charge.
- [x] Payment answers: `200` or `409` "already charged" → paid; `402` → `402 { reason }`, order unchanged, window not extended; `409` "attempt in progress" → `409` ("retry with the same card"); `502` / `504` / timeout → `503`, order unchanged.
- [x] Paid: one transaction with the check-and-set `PENDING_PAYMENT → PAID`, `paidAt`, and the fat `OrderPaid` in the outbox; `200 { orderId, status: "PAID" }`.
- [x] **Safety net:** if the check-and-set fails because the order changed during the charge (cancelled or expired), refund at once, log a warning, answer `409`.
- [x] Tests: unit tests for every Payment answer; a test where the order is cancelled while the (stubbed) charge runs → refund called, `409`; an integration test with WireMock Payment: paid order and `OrderPaid` on the topic with every field Delivery needs; the card number appears in no log line.

## Technical Notes / Constraints
- No retry by Order: Payment already repeats uncertain calls with the same key, and a repeat by the customer with the same card is safe.
- Read timeout ≥ 6 s (`PAYMENT_READ_TIMEOUT`); the expiry job's grace period must be longer ([task 13](13-payment-window-expiry-job.md)).

## Implementation
Done on 2026-10-17:
- `controllers/OrderController` `POST /orders/{orderId}/pay` → `OrderPaymentService.pay(orderId, customer, card)` → `200 dto/PaidOrderResponse { orderId, status }`.
- `dto/PayOrderRequest` and `dto/CardDetailsRequest` with Payment's rules (13–19 digits, month 1–12, year 2000–2099, CVV 3–4 digits, `validation/NotExpired` in the shop's time zone); `toString()` prints the number masked. Validation errors name the field and rule only (task 03).
- `services/OrderPaymentService`:
  - **Before the charge**, without a transaction: `OrderAccess.loadOwned` (`404`), not `PENDING_PAYMENT` → `409` with `orderStatus`, `reservationExpiresAt <= now` → `409 payment-window-expired`; no charge in either case.
  - **The charge** through the `Payments` port (no retry, task 05): `Paid` and `AlreadyCharged` → record; `Declined` → `402 { reason }`; `AttemptInProgress` → `409` "try again with the same card"; `ChargeUnavailable` → `503`. The order is unchanged in the last three.
  - **Recording** is a check-and-set in its own transaction: re-read the order; still `PENDING_PAYMENT` → `markPaid(now)`, `saveAndFlush` (the `@Version` check), `OrderPaid` in the outbox; already paid (a parallel request of the same customer recorded the same charge) → `200` without a second event; anything else (cancelled or expired during the charge) → **refund at once** and `409`. An optimistic-lock failure at the flush re-reads and decides again, at most three times.
- Tests: `OrderPaymentServiceTest` (14: paid with the order's total, already charged, declined with the window unchanged, attempt in progress, Payment unavailable, not awaiting payment, window ending exactly now, cancelled during the charge → refund and `409`, expired during the charge → refund, failed refund still `409`, paid meanwhile → `200` without refund or event, a lost optimistic lock re-read once, endless conflicts give up after three, the card masked in `toString()`), `OrderControllerTest` (the EP-ORD-03 part: `200`, five invalid cards → `400` without a call and without echoing the card, no card, `402` with reason, `409` window type, `409` with status, `404`), `integration/PaymentAndQueriesIntegrationTest` (4 on PostgreSQL 18, Kafka 4.1 and WireMock: paid, the charge carries the order's total, `OrderPaid` on the topic with key `orderId` and every field Delivery validates; **the order expires while WireMock is answering the charge** (a WireMock listener changes the row) → refund called with the total, `409`, still `EXPIRED`, no `OrderPaid`; declined → `402` and still payable; another customer's order → `404` without a charge. The two payment tests capture the whole log output and check that the card number appears in none of it).

## Decisions
Taken on 2026-10-17 (proposed during implementation):
- **An order found already paid on re-reading is a success, not a conflict.** Payment charges an order at most once, so a second request that also got `AlreadyCharged` concerns the same money; refunding it would refund the only charge.
- **A late payment is recorded even if the window passed during the charge**, as long as the order is still `PENDING_PAYMENT`: the charge started inside the window, and the expiry job waits for a grace period longer than a charge ([task 13](13-payment-window-expiry-job.md)).

## Not done here
- ~~**A failed refund after a lost race is only logged.**~~ Fixed on 2026-10-18: the refund is stored as owed and EP-ORD-JOB-04 repeats it ([task 17](17-review-fixes.md)).
