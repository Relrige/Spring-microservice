# Payment Service — Implementation Spec

An Anti-Corruption Layer (ACL) for the external payment gateway. It isolates all payment protocol specifics from the rest of the system. No other service knows about card details, gateway APIs, or transaction identifiers — they communicate only with this service. All commands arrive synchronously from Order Service; the service never initiates contact with other domain services.

A `MockPaymentGateway` is used during development: cards ending in `4242` succeed, cards ending in `0002` fail with "insufficient funds", cards ending in `5000` simulate a gateway timeout.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `PaymentTransaction` (`id`, `orderId`, `type`, `status`, `amount`, `currency`, `gatewayTransactionId`, `createdAt`) |
| **Events emitted** | none |
| **Events consumed** | none |
| **Callers** | Order Service only (internal; never called via API Gateway) |

---

## EP-PAY-01: Charge Card

**Type:** `HTTP POST /payments/charge`
**Caller:** Order Service (internal, synchronous call during payment flow)
**Auth:** Internal (no JWT; service-to-service trust — source IP or shared internal token TBD)

**Steps:**
1. Parse and validate the request body: `orderId` (UUID), `amount` (positive decimal), `currency` (ISO 4217, e.g., `"UAH"`), `cardDetails` (number, expiry, CVV).
2. Call the payment gateway (or `MockPaymentGateway` in development) with the charge parameters.
3. **On gateway success:**
   - Persist `PaymentTransaction(orderId, type=CHARGE, status=SUCCESS, amount, currency, gatewayTransactionId)`.
   - Return `200 OK { transactionId, status: "SUCCESS" }`.
4. **On gateway failure (declined, insufficient funds, etc.):**
   - Persist `PaymentTransaction(orderId, type=CHARGE, status=FAILED, amount, currency, gatewayTransactionId=null)`.
   - Return `402 Payment Required { reason }`.
5. **On gateway timeout / connectivity error:**
   - Persist `PaymentTransaction(orderId, type=CHARGE, status=FAILED, amount, currency)` with a timeout reason.
   - Return `502 Bad Gateway` (or `504 Gateway Timeout`) so Order Service can treat this as a retryable failure.

**Edge cases:**
- `amount` ≤ 0 → `400 Bad Request` (reject before calling the gateway)
- `orderId` already has a `CHARGE` with `status=SUCCESS` → `409 Conflict` (idempotency guard — do not double-charge)
- Card details structurally invalid (wrong length, expired date) → `400 Bad Request` (reject before calling the gateway if detectable client-side, otherwise the gateway will reject it)

**External calls:**
- `MockPaymentGateway` / real gateway: `charge(amount, currency, cardDetails)` — sync — external payment provider

**Emits:** none
**Refs:** SCN-C16, SCN-C17, customer-technical-insights §8

---

## EP-PAY-02: Refund Order

**Type:** `HTTP POST /payments/refund`
**Caller:** Order Service (internal, synchronous call during the cancellation saga)
**Auth:** Internal

**Steps:**
1. Parse and validate the request body: `orderId` (UUID), `amount` (positive decimal), `currency`.
2. Look up the original successful `PaymentTransaction` with `orderId` and `type=CHARGE` and `status=SUCCESS` to obtain the `gatewayTransactionId`.
3. If no successful charge transaction found → return `422 Unprocessable Entity` ("No successful charge found for this order — cannot refund").
4. Call the payment gateway refund API with `gatewayTransactionId` and `amount`.
5. **On gateway success:**
   - Persist `PaymentTransaction(orderId, type=REFUND, status=SUCCESS, amount, currency, gatewayTransactionId)`.
   - Return `200 OK { status: "SUCCESS" }`.
6. **On gateway failure:**
   - Persist `PaymentTransaction(orderId, type=REFUND, status=FAILED, ...)`.
   - Return `502 Bad Gateway` so the saga coordinator can decide how to handle it (retry / alert).

**Edge cases:**
- `orderId` has no prior successful charge → `422 Unprocessable Entity`
- Refund for this `orderId` already succeeded → `409 Conflict` (idempotency guard — do not double-refund)
- Gateway timeout during refund → persist `FAILED` record, return `504`; the saga must handle retry or manual resolution

**External calls:**
- `MockPaymentGateway` / real gateway: `refund(gatewayTransactionId, amount)` — sync — external payment provider

**Emits:** none
**Refs:** SCN-S07 (step 3), customer-technical-insights §8
