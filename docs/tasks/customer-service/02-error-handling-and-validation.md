---
status: DONE
service: customer-service
---
# Error Handling and Validation

## Context
All services answer errors with the same RFC 9457 `ProblemDetail` shape. Customer Service separates two kinds of client error: `400` for a malformed request and `422` for a well-formed request that breaks a cart rule. The `422` body carries a `violation` code and details, so the client can tell the user which product hit which limit.
*References:* [Customer Spec — Cart Aggregate, Error conventions](../../design/services-specs/customer-service-spec.md), [Delivery error handling task](../delivery-service/03-error-handling-and-validation.md)

## Acceptance Criteria
- [x] Copy the global exception handler and `ProblemDetail` factory from `delivery-service` (fields `type`, `title`, `status`, `detail`, `timestamp`, `service`), with `service = customer-service`.
- [x] `400` with `errors` for: invalid JSON, broken field rules (null fields, `quantity` < 1, `items` null), and a non-UUID path variable.
- [x] `CartItemNotFoundException` → `404` (used only by EP-CUST-05).
- [x] `CartRuleViolationException` → `422` with type `cart-rule-violation` and the properties from the spec:
  - `violation = TOO_MANY_PRODUCTS` with `resultingProductCount`, `maxProducts`
  - `violation = QUANTITY_LIMIT_EXCEEDED` with `productIds`, `maxQuantity`
  - `violation = DUPLICATE_PRODUCT` with `productIds`
- [x] Unexpected exceptions → `500` with a generic message; the stack trace is only logged.
- [x] Tests (WebMvc slice with a probe controller until the real controllers exist): each mapping returns the documented status, `type` and extra properties.

## Technical Notes / Constraints
- Keep the copied handler close to Delivery's version so later fixes can be applied to every copy.
- `CartRuleViolationException` is thrown by the `Cart` aggregate ([task 05](05-cart-aggregate-and-persistence.md)); it belongs to the domain, not to the web layer. The handler only translates it.
- `quantity > 10` is a business rule (`422`), not a field rule. Do not put `@Max(10)` on request DTOs, or it would come back as `400`.

## Implementation Notes (2026-10-16)
- **`controllers/GlobalExceptionHandler`** is a copy of Delivery's (same `problem(...)` factory and `400` handling for invalid fields, malformed JSON and non-UUID path values), with `service = customer-service` and the Customer exceptions instead of Delivery's.
- **Mappings:** `CartItemNotFoundException` → `404 not-found`; `CartRuleViolationException` → `422 cart-rule-violation` with `violation` and the extra properties; anything else → `500 internal-error`, stack trace only in the log.
- **`CartRuleViolationException`** has one factory per violation (`tooManyProducts(count)`, `quantityLimitExceeded(productIds)`, `duplicateProduct(productIds)`) that builds the documented extra properties, so the handler only copies them. `productIds` are de-duplicated and sorted, so the response does not depend on request order.
- **Limits** are constants in `entities/CartLimits` (`MAX_PRODUCTS = 10`, `MAX_QUANTITY = 10`), the one place task 05's `Cart` reads them from.
- **Verified (2026-10-16):** `GlobalExceptionHandlerTest` (WebMvc slice with a probe controller, 8 cases) passes.
