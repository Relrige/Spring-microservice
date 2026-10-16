---
status: TODO
service: customer-service
---
# Error Handling and Validation

## Context
All services answer errors with the same RFC 9457 `ProblemDetail` shape. Customer Service separates two kinds of client error: `400` for a malformed request and `422` for a well-formed request that breaks a cart rule. The `422` body carries a `violation` code and details, so the client can tell the user which product hit which limit.
*References:* [Customer Spec — Cart Aggregate, Error conventions](../../design/services-specs/customer-service-spec.md), [Delivery error handling task](../delivery-service/03-error-handling-and-validation.md)

## Acceptance Criteria
- [ ] Copy the global exception handler and `ProblemDetail` factory from `delivery-service` (fields `type`, `title`, `status`, `detail`, `timestamp`, `service`), with `service = customer-service`.
- [ ] `400` with `errors` for: invalid JSON, broken field rules (null fields, `quantity` < 1, `items` null), and a non-UUID path variable.
- [ ] `CartItemNotFoundException` → `404` (used only by EP-CUST-05).
- [ ] `CartRuleViolationException` → `422` with type `cart-rule-violation` and the properties from the spec:
  - `violation = TOO_MANY_PRODUCTS` with `resultingProductCount`, `maxProducts`
  - `violation = QUANTITY_LIMIT_EXCEEDED` with `productIds`, `maxQuantity`
  - `violation = DUPLICATE_PRODUCT` with `productIds`
- [ ] Unexpected exceptions → `500` with a generic message; the stack trace is only logged.
- [ ] Tests (WebMvc slice with a probe controller until the real controllers exist): each mapping returns the documented status, `type` and extra properties.

## Technical Notes / Constraints
- Keep the copied handler close to Delivery's version so later fixes can be applied to every copy.
- `CartRuleViolationException` is thrown by the `Cart` aggregate ([task 05](05-cart-aggregate-and-persistence.md)); it belongs to the domain, not to the web layer. The handler only translates it.
- `quantity > 10` is a business rule (`422`), not a field rule. Do not put `@Max(10)` on request DTOs, or it would come back as `400`.
