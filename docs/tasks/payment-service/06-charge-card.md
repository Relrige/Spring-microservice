---
status: DONE
service: payment-service
---
# EP-PAY-01: Charge Card (Internal)

## Context
Order Service calls this endpoint when a customer pays for an order (EP-ORD-03). Payment Service validates the request, asks the gateway to charge the card, records the outcome as a `PaymentTransaction` and answers with a status code Order Service can act on. The main risk is a **double charge**, so the idempotency guard is the core of the task.
*References:* [Payment Spec — EP-PAY-01](../../design/services-specs/payment-service-spec.md), [Order Spec — EP-ORD-03](../../design/services-specs/order-service-spec.md), [Customer Scenarios (SCN-C16, SCN-C17)](../../design/scenarios/customer-scenarios.md), [Persistence](02-payment-transaction-entity-and-persistence.md), [Gateway ACL](05-payment-gateway-acl-and-mock.md)

## Acceptance Criteria
- [x] `POST /payments/charge` accepts `{ orderId, amount, currency, cardDetails: { number, expiryMonth, expiryYear, cvv } }` (decide the expiry format and update the spec).
- [x] Validation → `400` **before** the gateway is called: `orderId` is a UUID; `amount > 0` with at most 2 decimal places; `currency` is a valid ISO 4217 code (decide whether only `UAH` is accepted); card number 13–19 digits (Luhn check optional, decide); expiry not in the past; CVV 3–4 digits.
- [x] The order already has a `SUCCESS` `CHARGE` → `409 Conflict`; the gateway is **not** called.
- [x] **Approved:** persist `CHARGE/SUCCESS` with `gatewayTransactionId`; return `200 { transactionId, status: "SUCCESS" }`.
- [x] **Declined:** persist `CHARGE/FAILED` with the reason; return `402 { reason }`. The customer can retry, so a later charge for the same order is allowed.
- [x] **Timeout / gateway unavailable:** persist `CHARGE/FAILED` with the reason; return `504` / `502`.
- [x] Every charge is sent with an idempotency key built as described in *Decision* below, and the key is stored on the row.
- [x] Two **concurrent** charges for the same `orderId` take the customer's money **once**: both send the same key, the gateway processes it once, the first request answers `200`, the second `409`. Proven by a concurrent integration test that also asserts the mock charged once.
- [x] Route rule from [task 04](04-trusted-header-security.md) applies (`hasRole("SERVICE")`).
- [x] Tests: service unit tests with a stubbed `PaymentGateway` for each outcome; controller slice tests for validation and status codes; integration tests on PostgreSQL with the mock gateway (cards `4242`, `0002`, `5000`), the repeated charge (`409`) and the concurrent race.

## Technical Notes / Constraints
- **Do not hold a database transaction open while calling the gateway.** A slow provider would keep a connection and locks for seconds. Typical shape: check → call the gateway (no transaction) → save the result in a short transaction.
- With the idempotency key, the partial unique index from [task 02](02-payment-transaction-entity-and-persistence.md) **is** reached in normal operation: in the concurrent case both requests get the same approved result from the gateway and both try to insert `CHARGE/SUCCESS`. Catch exactly that unique-index violation and answer `409`; let any other constraint violation stay a `500`. This differs from Inventory's reservation, where the index is only a last-resort guard (see its task 13).
- Order Service's behaviour on each code is in EP-ORD-03; agree with it that `409` for its own `orderId` means "already paid" (as with Inventory's reservation `409`).

## Decision
Agreed on 2026-10-15: **the gateway de-duplicates by an idempotency key** (option 3 of the original list; a per-order lock and a `PENDING` row were rejected). The provider is the only party that knows whether money was taken, so it is the right place to make a repeated request harmless. The same key also makes a retry after a timeout safe ([task 10](10-gateway-call-resilience.md)).

**What the key is (proposed, confirm before implementing).** The key cannot simply be `orderId`: a provider replays the stored result for a known key, so after a decline (`0002`) every new attempt with another card would get the same decline forever. Instead:

```
idempotencyKey = "charge:" + orderId + ":" + attempt
attempt        = 1 + number of distinct keys of this order's CHARGE rows that ended in a DEFINITIVE decline
```

- A **definitive decline** (`insufficient_funds`, `card_declined`) ends an attempt: the next request gets `attempt + 1`, so the customer can pay with another card.
- An **uncertain** outcome (`gateway_timeout`, `gateway_unavailable`) does **not** end the attempt: the next request (Order's retry, or the customer clicking again) reuses the same key, and the gateway either replays the success or processes the charge for the first time. Never twice.
- Two concurrent requests read the same rows, compute the same key, and the gateway processes it once.
- A repeat with the same key but another card while the first attempt is still uncertain → the gateway answers `IdempotencyKeyMismatch` → `409` ("previous payment attempt is still being resolved"). The customer must retry with the same card or wait for Order's retry to settle it.

Open point for the team: the attempt count is read without a lock. Two concurrent requests right after a decline both compute the same new key, which is correct. Check whether any interleaving can produce two *different* keys for one undecided attempt; if one is found, add a per-order advisory lock around the count only (not around the gateway call).

## Decisions (implementation, 2026-10-15)
- **Expiry format:** `expiryMonth` (1–12) and `expiryYear` (4 digits, 2000–2099); a card is valid until the end of its expiry month in Europe/Kyiv (`@NotExpired`).
- **Currency:** `UAH` only. The shop sells in Ukraine; another currency would need a provider account for it.
- **No Luhn check:** the provider validates the number; the length check (13–19 digits) catches typos in the format.
- **`transactionId` in the response is our `PaymentTransaction.id`**, not the provider's ID (the ACL keeps provider IDs inside the service).
- **Open point closed:** two *different* keys for one undecided attempt cannot occur. The counter only moves when a definitive decline of that attempt is stored, and that happens only after the provider has answered for that key, so the attempt is decided by then. Concurrent requests read the same rows and compute the same key; no lock is needed.

## Implementation
- `dto/ChargeRequest`, `dto/CardDetailsRequest` (masked `toString()`), `dto/PaymentResponse`; `validation/NotExpired` (+ validator).
- `services/PaymentService.charge`: `409` if already charged → key `charge:<orderId>:<attempt>` → gateway (no DB transaction around it) → `Approved` saves `SUCCESS` (a violation of `uq_payment_transactions_successful_charge` becomes `409`, any other constraint violation stays `500`), `Declined` saves `FAILED` + `402`, timeout / unavailable save `FAILED` (uncertain) and are passed on as `504` / `502`, `IdempotencyKeyMismatch` → `PaymentAttemptInProgressException` (`409`, nothing saved).
- `controllers/PaymentController` (`POST /payments/charge`, `/refund`); the test stand-in `PaymentRoutesProbeController` from task 04 is deleted and the security tests use the real controller.
- Tests: `PaymentServiceTest$Charge` (9), `PaymentControllerTest` (charge part: success, current-month expiry, 5 invalid order fields, 7 invalid card cases asserting the value is not echoed, 402/409/409/504/502), `ChargeIntegrationTest` (8: first-attempt key, repeat `409`, decline then another card under key `:2`, lost answer `504` then repeat `200` with **one** charge at the mock, another card after a lost answer `409`, 10 concurrent charges → one `UUID`, nine `409`, one charge at the mock, invalid request never reaches the gateway, no token `401`), `GatewayIsolationTest` (the ArchUnit-style guard deferred from task 05: `PaymentService` depends on the `PaymentGateway` port only).

## Notes for the Order Service payment client
- `409` for its own `orderId` means "already paid"; treat it as success. The title `Payment attempt in progress` is the exception: the earlier request must be repeated with the same card.
- `502` / `504` are safe to repeat with the **same** request; Payment Service then returns the real outcome.
- Order Service forwards card data, so the rules of the spec's *Card data* section apply there too.
