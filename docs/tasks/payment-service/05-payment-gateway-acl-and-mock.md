---
status: DONE
service: payment-service
---
# Payment Gateway Port (Anti-Corruption Layer) and MockPaymentGateway

## Context
The reason Payment Service exists is to be an **Anti-Corruption Layer**: the rest of the system speaks VoltStore's language (`orderId`, `amount`, "declined"), and only this service knows the provider's protocol, error codes and transaction IDs. In code this is a **port** (an interface in our terms) with one **adapter** per provider. During development the adapter is `MockPaymentGateway`, which simulates the outcomes the rest of the flow must handle.
*References:* [Payment Spec — overview](../../design/services-specs/payment-service-spec.md), [Customer Scenarios (SCN-C16, SCN-C17)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [x] A `PaymentGateway` interface in the `gateway` package with `charge(ChargeCommand)` and `refund(RefundCommand)`. Its inputs and outputs are our own types (for example `GatewayResult.Approved(gatewayTransactionId)`, `GatewayResult.Declined(reason)`), never provider DTOs.
- [x] Gateway problems are reported as exceptions of our own: `PaymentGatewayTimeoutException` and `PaymentGatewayUnavailableException` (mapped in [task 03](03-error-handling-and-validation.md)).
- [x] `MockPaymentGateway` implements the rules of the spec, chosen by the card number's **last four digits**:
  - `4242` → approved, returns a generated `gatewayTransactionId` (for example `mock_ch_<uuid>`)
  - `0002` → declined, reason `insufficient_funds`
  - `5000` → simulated timeout (throws `PaymentGatewayTimeoutException` after a configurable delay)
  - any other number → declined, reason `card_declined` (decide and document)
- [x] Mock refunds: approved for a `gatewayTransactionId` the mock issued, otherwise declined.
- [x] **Idempotency keys** (decision in [task 06](06-charge-card.md)): `charge` and `refund` take an `idempotencyKey`. The mock keeps a key → result store and behaves like a real provider:
  - a key seen before with the **same** parameters → the stored result is returned again, without a new charge and without the delay;
  - a key seen before with **different** parameters (amount, currency, card) → `IdempotencyKeyMismatch` result;
  - two concurrent calls with the same key → only one is processed, the other gets its result (lock per key).
- [x] The `5000` card simulates **"charged, but the answer was lost"**: the mock stores an approved result for the key, then throws the timeout. A repeated call with the same key returns that approved result. This is the case [task 10](10-gateway-call-resilience.md) is built around.
- [x] Tests for the key rules: replay returns the same `gatewayTransactionId`; mismatch; concurrent calls charge once; `5000` then replay returns approved.
- [x] The adapter is selected by configuration (for example `payment.gateway.provider=mock`), so a real adapter can be added later without touching the service code. Only the mock exists in this project.
- [x] Unit tests for each card rule and for refund of a known/unknown transaction.

## Technical Notes / Constraints
- The mock is part of the application, not a test double: it runs in Compose and Kubernetes too, because there is no real provider.
- The mock's key store is in memory, so it is per pod and is lost on restart. A real provider is one shared system. Keep `replicas: 1` for Payment Service and write this limitation into the spec; a database-backed mock store would remove it but mixes the fake provider's data into our database.
- Keep the simulated delay configurable (`payment.gateway.mock.timeout-delay`) and short in tests.
- Think about where the "timeout" belongs. A real adapter would be an HTTP client with a read timeout (`RestClient` + request factory timeouts, as in Catalog [task 22](../catalog-service/22-inventory-call-resilience.md)); the mock can only imitate it. [Task 10](10-gateway-call-resilience.md) discusses what a timeout means for money.
- Nothing provider-specific (status codes, field names, IDs format) may leak into `services` or `controllers`; a test or an ArchUnit-style check that `services` does not import the mock is a cheap guard.

## Implementation
Done on 2026-10-15:
- Port `gateway/PaymentGateway` with `charge(ChargeCommand)` / `refund(RefundCommand)`; our own types: `GatewayResult` (sealed: `Approved(gatewayTransactionId)`, `Declined(DeclineReason)`, `IdempotencyKeyMismatch()`), `DeclineReason`, `PaymentCard` (masked `toString()`, so a logged `ChargeCommand` shows `**** 4242`; this is part of [task 08](08-card-data-protection.md) done early).
- `MockPaymentGateway`, active when `payment.gateway.provider=mock` (`PAYMENT_GATEWAY_PROVIDER`, default `mock`); delay `payment.gateway.mock.timeout-delay` (default `2s`, `0s` in tests).
  - `4242` approved (`mock_ch_<uuid>`), `0002` `INSUFFICIENT_FUNDS`, any other number `CARD_DECLINED`; `5000` is stored as approved, then the timeout is thrown after the delay; a repeat with the same key returns the approval at once.
  - Refund: approved (`mock_re_<uuid>`) only for a charge the mock approved, otherwise `REFUND_REJECTED`.
  - Key store: one lock per key; a stored key with the same fingerprint (amount normalized, so `100` = `100.00`) replays the result, with another fingerprint returns `IdempotencyKeyMismatch`. `approvedChargeCount()` / `approvedRefundCount()` let later tests prove "charged once".
- Tests: `MockPaymentGatewayTest` (11, including 16 concurrent calls with one key → one charge, and the "repeat of a decline returns the same decline" case that motivates the attempt counter), `PaymentCardTest` (2), and the context test asserts the mock is the selected `PaymentGateway`.

## Not done here
- The ArchUnit-style guard ("`services` does not import the mock") needs a `services` package, which arrives in task 06; add it there.
- The mock has no trigger for `PaymentGatewayUnavailableException`; only a real HTTP adapter would raise it. Task 10 may add a card suffix for it if a test needs one.
