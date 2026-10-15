---
status: DONE
service: payment-service
---
# Consistent Error Handling

## Context
Payment Service answers only Order Service, but the status codes it returns are part of the payment flow: Order Service decides from them whether the customer may retry with another card (`402`), whether the call can be retried (`502`/`504`) or whether it was already done (`409`). The codes must therefore be exact and the body must use the platform's `ProblemDetail` shape.
*References:* [Payment Spec](../../design/services-specs/payment-service-spec.md), [Order Spec — EP-ORD-03, EP-ORD-05](../../design/services-specs/order-service-spec.md), [Inventory error handling task](../inventory-service/03-error-handling-and-validation.md)

## Acceptance Criteria
- [x] A `@RestControllerAdvice` (extending `ResponseEntityExceptionHandler`) maps validation failures to `400` with a field-level `errors` map, malformed JSON to `400` and type mismatches to `400`.
- [x] Domain exceptions and mapping:
  - `PaymentDeclinedException` (declined, insufficient funds, invalid card at the gateway) → `402` with a `reason` property
  - `OrderAlreadyChargedException` → `409`
  - `OrderAlreadyRefundedException` → `409`
  - `NoSuccessfulChargeException` → `422`
  - `PaymentGatewayUnavailableException` (connection error, gateway `5xx`) → `502`
  - `PaymentGatewayTimeoutException` → `504`
- [x] Unexpected exceptions return `500` with a generic message and no stack trace; the stack trace is logged server-side.
- [x] Error format is the RFC 9457 `ProblemDetail` shape used by the other services: `type`, `title`, `status`, `detail`, `timestamp`, `service`.
- [x] **No error body or log line contains card data**: the `errors` map for a rejected `cardDetails.number` names the field but does not echo its value (Spring's default `rejectedValue` must not leak into the response).
- [x] Tests cover each mapped exception through a controller slice test.

## Technical Notes / Constraints
- `400` vs `422` as in the other services: `400` = malformed request or field rule violated (for example `amount <= 0`, expired card); `422` = well-formed request that cannot be done in the current state (refund with no prior charge).
- `402` is a business answer, not an error of the system: log it at `INFO`, not `ERROR`.
- Exceptions may be added together with the task that first throws them, as long as the mapping lives in this one advice.

## Implementation
Done on 2026-10-15:
- `controllers/GlobalExceptionHandler` (same shape as Inventory's): `PaymentDeclinedException` → `402` + `reason` (logged at `INFO`), `OrderAlreadyChargedException` / `OrderAlreadyRefundedException` → `409`, `NoSuccessfulChargeException` → `422`, `PaymentGatewayUnavailableException` → `502`, `PaymentGatewayTimeoutException` → `504` (both logged at `WARN`, fixed client text without provider details), anything else → `500`.
- Validation errors list the field and the rule's message only (`getDefaultMessage()`, never `getRejectedValue()`).
- Tests: `GlobalExceptionHandlerTest` (10) with a probe controller; two of them capture the log output and assert that an invalid card number and CVV appear neither in the response nor in the log, also for malformed JSON.
- Exceptions still to come with their tasks: the idempotency-key mismatch (`409`, task 06) and the refund amount mismatch (`422`, task 07).
