---
status: DONE
service: order-service
---
# Consistent Error Handling

## Context
Order Service is the service customers talk to during checkout, and it passes on the answers of four others. Its errors must use the platform's `ProblemDetail` shape, keep the statuses the spec defines (for example `402` with Payment's `reason`, `422` with Inventory's `insufficientItems`), and never leak upstream hosts, statuses or card data.
*References:* [Order Spec — Responses and errors](../../design/services-specs/order-service-spec.md), [Inventory error handling task](../inventory-service/03-error-handling-and-validation.md), [Payment error handling task](../payment-service/03-error-handling-and-validation.md)

## Acceptance Criteria
- [x] A `@RestControllerAdvice` (extending `ResponseEntityExceptionHandler`) maps validation failures to `400` with a field-level `errors` map, and malformed JSON and type mismatches (a non-UUID `{orderId}`) to `400`.
- [x] Domain exceptions and mapping (each added with the task that first throws it, but mapped here):
  - `OrderNotFoundException` (also used for another customer's order) → `404`
  - `IneligibleItemsException` → `422` with `ineligibleItems: [ { productId, reason } ]`
  - `InsufficientStockException` → `422` with `insufficientItems` (Inventory's list, unchanged)
  - `InvalidOrderStateException` → `409` (the message names the current status)
  - `PaymentWindowExpiredException` → `409`, type `payment-window-expired`
  - `PaymentAttemptInProgressException` → `409`
  - `PaymentDeclinedException` → `402` with `reason`
  - `TtnNotGeneratedException` → `409`
  - `DependencyUnavailableException(service)` → `503` with a fixed `detail` and `Retry-After`
- [x] Unexpected exceptions → `500` with a generic message; the stack trace is logged.
- [x] Error shape: `type`, `title`, `status`, `detail`, `timestamp`, `service` (`order-service`).
- [x] Validation errors never echo rejected values (card data in EP-ORD-03), as in Payment.
- [x] Tests: each mapping through a slice test with a probe controller; a `400` for an invalid card number does not contain the number.

## Technical Notes / Constraints
- `400` vs `422` as in the other services: `400` = malformed or a field rule broken; `422` = well-formed but not possible now (inactive product, no stock).
- An upstream's `5xx` never reaches the customer as is: it becomes `503` with Order's fixed text.

## Implementation
Done on 2026-10-16:
- `controllers/GlobalExceptionHandler` (the shape of Inventory's and Payment's) maps every exception listed above; `InvalidOrderStateException` also carries an `orderStatus` property. `DependencyUnavailableException` → `503` with a fixed `detail` and `Retry-After: 10`; the cause (host, status) is only logged at `ERROR`. `PaymentDeclinedException` is logged at `INFO` (a business answer).
- Exceptions in `exceptions/`; the item lists of the two `422`s are `dto/IneligibleItem(productId, reason)` and `dto/InsufficientItem(productId, requested, available)` (Inventory's field names).
- Validation errors use `getDefaultMessage()` only, never the rejected value.
- Tests: `GlobalExceptionHandlerTest` (14, with a probe controller): validation with field errors; an invalid card number named but in neither the body nor the log; malformed JSON; a non-UUID path variable; each domain exception's status, type and extra property; `503` with fixed text and `Retry-After` while the host appears only in the log; `500` without a trace.
