---
status: DONE
service: customer-service
---
# Trusted-Header Security

## Context
Every Customer Service endpoint is called by a logged-in customer through the API Gateway. The gateway validates the JWT and forwards `X-User-Id` and `X-User-Role`; the service trusts these headers and takes `customerId` from `X-User-Id`. A customer can only ever reach their own profile and cart, because no endpoint takes a customer ID from the path or body.

Customer Service has no internal endpoints and calls no other service over HTTP.
*References:* [Security Model](../../design/services-requirements/security-model.md), [Customer Spec](../../design/services-specs/customer-service-spec.md), [Delivery security task](../delivery-service/04-trusted-header-security.md)

## Acceptance Criteria
- [x] Copy the `security` package (trusted-header filter, `AuthenticatedUser`, `UserRole`, security chain, `401`/`403` handlers) and the `CorrelationIdFilter` from `delivery-service`, keeping them as identical as possible.
- [x] Route rules, then `denyAll()`:
  - `/customers/me/profile` (`GET`, `PUT`) → `hasRole("CUSTOMER")`
  - `/customers/cart`, `/customers/cart/merge`, `/customers/cart/items`, `/customers/cart/items/{productId}` (all methods from the API contracts) → `hasRole("CUSTOMER")`
  - `/actuator/health/**` → `permitAll()`
- [x] Controllers get `customerId` from the authenticated principal, never from a request parameter.
- [x] The role check runs before the body is parsed (no validation feedback to unauthorized callers).
- [x] Tests: a route-coverage test (every controller route has a rule, added with the first controller); `401` anonymous, `200` for `CUSTOMER`, `403` for every other role including `ADMIN`.

## Technical Notes / Constraints
- **Decide and record:** whether to copy the internal-token filter as well. Without internal routes it is not needed. Copying it keeps the package identical to the other services; leaving it out keeps the service minimal. If it is copied, `AUTH_INTERNAL_TOKEN` must also go into the configuration and secrets ([task 09](09-containerization-and-deployment.md)).
- The customer routes are added to the gateway in [API gateway task 10](../api-getaway/10-remaining-service-routes.md).

## Implementation Notes (2026-10-16)
- **Copied from Delivery** with only the package renamed: `security` (`AuthenticatedUser`, `UserRole`, `TrustedHeaderAuthenticationFilter`, `SecurityErrorHandlers`) and `filters` (`CorrelationIdFilter`, `CorrelationIdKeys`). The correlation filter's comment was adjusted, since Customer makes no outgoing calls.
- **Decision: no internal-token filter.** Customer Service has no internal routes and calls no other service, so `InternalTokenAuthenticationFilter`, `InternalTokenProperties` and `AUTH_INTERNAL_TOKEN` were not copied. That keeps the configuration and the Kubernetes secrets minimal (task 09 has no `customer-secrets`). If an internal route is added later, copy the filter from Delivery then.
- **`SecurityConfig`:** `/actuator/health/**` open; the nine contract routes (profile `GET`/`PUT`; cart `GET`/`PUT`/`DELETE`; `POST /customers/cart/merge`; `POST /customers/cart/items`; `PATCH`/`DELETE /customers/cart/items/{productId}`) → `CUSTOMER`, one rule per method and path; everything else `denyAll()`. `UserDetailsServiceAutoConfiguration` is excluded as in the other services.
- **`customerId`** is read in controllers with `@AuthenticationPrincipal AuthenticatedUser`; no route takes a customer ID from the path or body.
- **Deviation (test order):** there is no real controller yet, so `SecurityConfigTest` checks the rules against a probe controller (`401` anonymous, `200` for `CUSTOMER`, `403` for every other role including `ADMIN` with a malformed body, the principal carries the header's ID, malformed headers are anonymous, unlisted routes are denied). `RouteSecurityRulesTest` over the real controllers is added with the first controller in task 04.
- **Verified (2026-10-16):** `SecurityConfigTest` (30 cases) and the whole module's tests pass.
