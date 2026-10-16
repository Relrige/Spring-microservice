---
status: TODO
service: customer-service
---
# Trusted-Header Security

## Context
Every Customer Service endpoint is called by a logged-in customer through the API Gateway. The gateway validates the JWT and forwards `X-User-Id` and `X-User-Role`; the service trusts these headers and takes `customerId` from `X-User-Id`. A customer can only ever reach their own profile and cart, because no endpoint takes a customer ID from the path or body.

Customer Service has no internal endpoints and calls no other service over HTTP.
*References:* [Security Model](../../design/services-requirements/security-model.md), [Customer Spec](../../design/services-specs/customer-service-spec.md), [Delivery security task](../delivery-service/04-trusted-header-security.md)

## Acceptance Criteria
- [ ] Copy the `security` package (trusted-header filter, `AuthenticatedUser`, `UserRole`, security chain, `401`/`403` handlers) and the `CorrelationIdFilter` from `delivery-service`, keeping them as identical as possible.
- [ ] Route rules, then `denyAll()`:
  - `/customers/me/profile` (`GET`, `PUT`) → `hasRole("CUSTOMER")`
  - `/customers/cart`, `/customers/cart/merge`, `/customers/cart/items`, `/customers/cart/items/{productId}` (all methods from the API contracts) → `hasRole("CUSTOMER")`
  - `/actuator/health/**` → `permitAll()`
- [ ] Controllers get `customerId` from the authenticated principal, never from a request parameter.
- [ ] The role check runs before the body is parsed (no validation feedback to unauthorized callers).
- [ ] Tests: a route-coverage test (every controller route has a rule, added with the first controller); `401` anonymous, `200` for `CUSTOMER`, `403` for every other role including `ADMIN`.

## Technical Notes / Constraints
- **Decide and record:** whether to copy the internal-token filter as well. Without internal routes it is not needed. Copying it keeps the package identical to the other services; leaving it out keeps the service minimal. If it is copied, `AUTH_INTERNAL_TOKEN` must also go into the configuration and secrets ([task 09](09-containerization-and-deployment.md)).
- The customer routes are added to the gateway in [API gateway task 10](../api-getaway/10-remaining-service-routes.md).
