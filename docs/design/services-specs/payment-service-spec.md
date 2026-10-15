# Payment Service — Implementation Spec

An Anti-Corruption Layer (ACL) for the external payment gateway. It isolates all payment protocol specifics from the rest of the system. No other service knows about card details, gateway APIs, or transaction identifiers — they communicate only with this service. All commands arrive synchronously from Order Service; the service never initiates contact with other domain services.

A `MockPaymentGateway` is used in every environment (there is no real provider in this project), selected by `PAYMENT_GATEWAY_PROVIDER=mock`. The outcome depends on the card number's last four digits: `4242` succeeds, `0002` fails with "insufficient funds", `5000` is charged but the answer is lost (a timeout is reported after `PAYMENT_GATEWAY_TIMEOUT`; the internal repeat with the same idempotency key gets the approval, so the request ends with `200` after about 2.5 s), any other number is declined.

**Known limits** (accepted): real providers keep idempotency keys for a limited time (Stripe: 24 h), far longer than Order's payment window; the mock keeps them in memory, so a Payment Service restart between a lost answer and its repeat loses the protection, and the service must run as one replica. No circuit breaker: there is one caller, low volume, and the repeats are already bounded.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `PaymentTransaction` (`id`, `orderId`, `type`, `status`, `amount`, `currency`, `gatewayTransactionId`, `failureReason`, `idempotencyKey`, `createdAt`); append-only, see [entities](../entities/entities.md) |
| **Events emitted** | none |
| **Events consumed** | none |
| **Callers** | Order Service only (internal; never called via API Gateway) |
| **Auth** | `X-Internal-Token` → `ROLE_SERVICE` on both routes; no user may call the service, user headers are ignored |
| **Currency** | `UAH` only |

### Idempotency keys
Double charges and refunds are prevented by the gateway, not by locks: every request carries an idempotency key, and the provider processes a key once (a repeat with the same parameters gets the stored result; a repeat with other parameters is refused as a mismatch).

- Charge: `charge:<orderId>:<attempt>`, where `attempt` = 1 + the number of earlier attempts of the order that ended in a **definitive** decline (`INSUFFICIENT_FUNDS`, `CARD_DECLINED`). An **uncertain** outcome (`GATEWAY_TIMEOUT`, `GATEWAY_UNAVAILABLE`) does not end the attempt, so the next request reuses the key and cannot charge twice.
- Refund: `refund:<orderId>` (one full refund per order).
- Two concurrent requests for one attempt send the same key and both get the provider's single result; the partial unique index (one `SUCCESS` per order and type) lets one of them record it, the other answers `409`.

---

## EP-PAY-01: Charge Card

**Type:** `HTTP POST /payments/charge`
**Caller:** Order Service (internal, synchronous call during payment flow)
**Auth:** Internal (`X-Internal-Token`)

**Request:** `{ orderId, amount, currency, cardDetails: { number, expiryMonth, expiryYear, cvv } }`

**Steps:**
1. Validate the request body → `400` with a field-level `errors` map (rejected values are never repeated): `orderId` (UUID); `amount` (> 0, at most 2 decimal places); `currency` (`"UAH"`); `cardDetails.number` (13–19 digits, no Luhn check — the provider validates the number); `expiryMonth` (1–12), `expiryYear` (2000–2099), not before the current month (Europe/Kyiv); `cvv` (3–4 digits).
2. If the order already has a `CHARGE` with `status=SUCCESS` → `409 Conflict`; the gateway is not called.
3. Build the idempotency key (see above) and call the gateway. No database transaction is open during the call.
4. **On gateway success:**
   - Persist `PaymentTransaction(type=CHARGE, status=SUCCESS, gatewayTransactionId, idempotencyKey)`.
   - Return `200 OK { transactionId, status: "SUCCESS" }`. `transactionId` is our `PaymentTransaction.id`; the provider's ID never leaves the service.
5. **On a decline (insufficient funds, card declined):**
   - Persist `PaymentTransaction(type=CHARGE, status=FAILED, failureReason)`.
   - Return `402 Payment Required { reason }` (`insufficient_funds`, `card_declined`). The next charge of the order uses a new key, so the customer can try another card.
6. **On gateway timeout / connectivity error** (no answer within `PAYMENT_GATEWAY_TIMEOUT`, default 2 s): the outcome is unknown, the money may have been taken. The call is repeated with the **same** idempotency key (`PAYMENT_GATEWAY_MAX_RETRIES`, default 1, after `PAYMENT_GATEWAY_RETRY_DELAY`, default 300 ms + jitter); the provider replays the stored result or processes the request for the first time, never twice. Only the final outcome is recorded. If every attempt stays unanswered:
   - Persist `PaymentTransaction(type=CHARGE, status=FAILED, failureReason=GATEWAY_TIMEOUT | GATEWAY_UNAVAILABLE)`.
   - Return `504 Gateway Timeout` / `502 Bad Gateway`. Repeating the same request later is safe (it reuses the key) and returns the real outcome.
   - Worst case per request is about 4.5 s with the defaults; Order Service's read timeout for `/payments/**` must be longer (6 s or more).
7. **On an idempotency-key mismatch** (the unresolved attempt was made with other details, e.g. another card after a timeout) → `409 Conflict` ("Payment attempt in progress"); nothing is recorded. Repeat the original request to settle it.

**Edge cases:**
- `amount` ≤ 0, wrong currency, invalid or expired card → `400` (before the gateway is called)
- Order already charged, including the losing side of two concurrent charges → `409`; Order Service treats it as "already paid"
- Mismatch after an uncertain attempt → `409` "Payment attempt in progress"

**External calls:**
- `PaymentGateway.charge(idempotencyKey, amount, currency, card)` — sync — `MockPaymentGateway`

**Emits:** none
**Refs:** SCN-C16, SCN-C17, customer-technical-insights §8

---

## EP-PAY-02: Refund Order

**Type:** `HTTP POST /payments/refund`
**Caller:** Order Service (internal, synchronous call during the cancellation saga)
**Auth:** Internal (`X-Internal-Token`)

**Request:** `{ orderId, amount, currency }`

**Steps:**
1. Validate the request body as in EP-PAY-01 → `400`.
2. Look up the order's `CHARGE` with `status=SUCCESS`. If there is none → `422 Unprocessable Entity` ("No successful charge found for order … - cannot refund").
3. If the order already has a `REFUND` with `status=SUCCESS` → `409 Conflict`.
4. Only full refunds are supported: `amount` and `currency` must equal the charge's (`1499.9` equals `1499.90`), otherwise `422` ("Only full refunds are supported").
5. Call the gateway refund with the charge's `gatewayTransactionId` and the key `refund:<orderId>`.
6. **On gateway success:**
   - Persist `PaymentTransaction(type=REFUND, status=SUCCESS, gatewayTransactionId=<refund ID>)`.
   - Return `200 OK { transactionId, status: "SUCCESS" }`.
7. **On a rejected refund:** persist `REFUND/FAILED` (`REFUND_REJECTED`) and return `502 Bad Gateway { reason: "refund_rejected" }` so the saga coordinator can decide how to handle it (retry / alert).
8. **On gateway timeout / connectivity error:** repeated with the same key as in EP-PAY-01 step 6; if every attempt stays unanswered, persist `REFUND/FAILED` (`GATEWAY_TIMEOUT` / `GATEWAY_UNAVAILABLE`) and return `504` / `502`. A later repeat uses the same key, so it cannot refund twice.

**Edge cases:**
- No prior successful charge → `422`
- Refund already succeeded, including the losing side of two concurrent refunds → `409` (Order Service treats it as "already refunded")
- Partial amount or other currency → `422`

**External calls:**
- `PaymentGateway.refund(idempotencyKey, gatewayTransactionId, amount, currency)` — sync — `MockPaymentGateway`

**Emits:** none
**Refs:** SCN-S07 (step 3), customer-technical-insights §8

---

## Card Data

Card number, expiry and CVV pass through Order Service and Payment Service on every charge. They exist **only in memory**:

| Where | Card data |
|---|---|
| `POST /payments/charge` request body → `CardDetailsRequest` → `PaymentCard` → `PaymentGateway` adapter | yes, in memory for the duration of the request |
| `payment_transactions` (and any other table) | **never**, not even masked |
| Log lines | only masked: `**** **** **** 4242` (`toString()` of the DTO, the card and the gateway command is overridden) |
| Error responses | never: validation errors name the field and rule, not the rejected value |
| Request logging | not enabled for this service; do not enable `CommonsRequestLoggingFilter` with payloads or `DEBUG` logging of `org.springframework.web`, which prints rejected values of failed validation |

Order Service has the same obligations for the card data it forwards from the customer.

---

## Security and Routing

| Route | Rule |
|---|---|
| `POST /payments/charge` | `hasRole("SERVICE")` |
| `POST /payments/refund` | `hasRole("SERVICE")` |
| `/actuator/health/**` | public (probes) |
| anything else | `denyAll()` |

The API Gateway never routes `/payments/**`. The mock gateway keeps its idempotency keys in memory, so Payment Service runs as a single replica.
