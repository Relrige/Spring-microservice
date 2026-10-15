---
status: TODO
service: payment-service
---
# Internal-Only Security (Copy from Auth Service)

## Context
Every Payment Service endpoint is `Internal`: only Order Service may call it, and the API Gateway never routes `/payments/**`. Payment is therefore the simplest case of the security model: no user roles, only the internal-token filter that grants `ROLE_SERVICE`. Because the service moves money, a forgotten rule must fail closed.
*References:* [Security Model](../../design/services-requirements/security-model.md), [Trusted-Header Authentication (pilot)](../auth-service/08-trusted-header-authentication.md), [Inventory security task](../inventory-service/07-trusted-header-security.md), [API Gateway Spec — not routed](../../design/services-specs/api-gateway-spec.md)

## Acceptance Criteria
- [ ] Add `spring-boot-starter-security` and `spring-boot-starter-security-test`; exclude `UserDetailsServiceAutoConfiguration` in `application.yml`.
- [ ] Copy the `security` package from inventory-service (`InternalTokenAuthenticationFilter`, `InternalTokenProperties`, `SecurityErrorHandlers`, `SecurityConfig`, and the trusted-header classes if the team decides to keep the copies identical).
- [ ] `SecurityConfig` is stateless (no CSRF, sessions, form login, HTTP Basic), permits the health probes, and ends with `anyRequest().denyAll()`.
- [ ] Route rules: `POST /payments/charge` and `POST /payments/refund` → `hasRole("SERVICE")`.
- [ ] A request with user headers only (`X-User-Id`, `X-User-Role: ADMIN`) and no token → `401`/`403`; a request with a wrong token → `401`. Rendered as `ProblemDetail` with the shape from [task 03](03-error-handling-and-validation.md). The check runs before the body is parsed, so a rejected caller's card data is never bound.
- [ ] Add `auth.internal.token` configuration (`INTERNAL_TOKEN`, not blank, at least 32 characters, never logged) to `.env`, `.env.example`, `docker-compose.yml` and test properties.
- [ ] Tests: unit tests for the internal-token filter; `401`/`403`/success tests for both routes; a slice test proving the `denyAll()` fallback; a route coverage test like inventory's `RouteSecurityRulesTest`.

## Technical Notes / Constraints
- Do not use `@PreAuthorize`: the check must run before binding and validation.
- The shared token identifies "some VoltStore service", not Order Service in particular. For a payment service this is the weakest point of the model; write down in the PR which improvement the team would choose (per-service tokens, service JWTs, mTLS, or a `NetworkPolicy` that only lets the Order pod reach Payment).
- The `payment-secrets` Kubernetes Secret is wired in [task 09](09-containerization-and-deployment.md).
