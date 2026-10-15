---
status: DONE
service: payment-service
---
# Internal-Only Security (Copy from Auth Service)

## Context
Every Payment Service endpoint is `Internal`: only Order Service may call it, and the API Gateway never routes `/payments/**`. Payment is therefore the simplest case of the security model: no user roles, only the internal-token filter that grants `ROLE_SERVICE`. Because the service moves money, a forgotten rule must fail closed.
*References:* [Security Model](../../design/services-requirements/security-model.md), [Trusted-Header Authentication (pilot)](../auth-service/08-trusted-header-authentication.md), [Inventory security task](../inventory-service/07-trusted-header-security.md), [API Gateway Spec — not routed](../../design/services-specs/api-gateway-spec.md)

## Acceptance Criteria
- [x] Add `spring-boot-starter-security` and `spring-boot-starter-security-test`; exclude `UserDetailsServiceAutoConfiguration` in `application.yml`.
- [x] Copy the `security` package from inventory-service (`InternalTokenAuthenticationFilter`, `InternalTokenProperties`, `SecurityErrorHandlers`, `SecurityConfig`, and the trusted-header classes if the team decides to keep the copies identical).
- [x] `SecurityConfig` is stateless (no CSRF, sessions, form login, HTTP Basic), permits the health probes, and ends with `anyRequest().denyAll()`.
- [x] Route rules: `POST /payments/charge` and `POST /payments/refund` → `hasRole("SERVICE")`.
- [x] A request with user headers only (`X-User-Id`, `X-User-Role: ADMIN`) and no token → `401`/`403`; a request with a wrong token → `401`. Rendered as `ProblemDetail` with the shape from [task 03](03-error-handling-and-validation.md). The check runs before the body is parsed, so a rejected caller's card data is never bound.
- [x] Add `auth.internal.token` configuration (`INTERNAL_TOKEN`, not blank, at least 32 characters, never logged) to `.env`, `.env.example`, `docker-compose.yml` and test properties.
- [x] Tests: unit tests for the internal-token filter; `401`/`403`/success tests for both routes; a slice test proving the `denyAll()` fallback; a route coverage test like inventory's `RouteSecurityRulesTest`.

## Technical Notes / Constraints
- Do not use `@PreAuthorize`: the check must run before binding and validation.
- The shared token identifies "some VoltStore service", not Order Service in particular. For a payment service this is the weakest point of the model; write down in the PR which improvement the team would choose (per-service tokens, service JWTs, mTLS, or a `NetworkPolicy` that only lets the Order pod reach Payment).
- The `payment-secrets` Kubernetes Secret is wired in [task 09](09-containerization-and-deployment.md).

## Decisions
- **No trusted-header filter.** Payment has no user routes, so only `InternalTokenAuthenticationFilter`, `InternalTokenProperties` and `SecurityErrorHandlers` were copied (unchanged apart from the package). `X-User-*` headers are ignored and such a caller stays anonymous (`401`), even as `ADMIN`.

## Implementation
Done on 2026-10-15:
- `spring-boot-starter-security` (+ `-test`); `UserDetailsServiceAutoConfiguration` excluded.
- `SecurityConfig`: stateless, health probes public, `POST /payments/charge` and `POST /payments/refund` → `hasRole("SERVICE")`, then `denyAll()`.
- `auth.internal.token: ${INTERNAL_TOKEN}`; `INTERNAL_TOKEN` added to `.env.example` and the local `.env` (same value as Catalog's `AUTH_INTERNAL_TOKEN`); `docker-compose.yml` needs no change because it passes the whole `.env` (`env_file`); test value in `src/test/resources/application.properties`.
- The routes have no controller yet (tasks 06–07), so the tests use `support/PaymentRoutesProbeController`, a `@TestComponent` stand-in that is only loaded where a test imports it. **Delete it in task 06/07** and point `SecurityConfigTest` at the real controller (`RouteSecurityRulesTest` picks real controllers up by itself).
- Tests: `InternalTokenAuthenticationFilterTest` (4, copied), `SecurityConfigTest` (15: service allowed on both routes, anonymous / wrong token / every user role `401`, token wins over user headers, `401` before body validation, wrong method and unlisted route `403`, health open), `RouteSecurityRulesTest` (1).
- **Kubernetes:** the pod now needs `INTERNAL_TOKEN` and will not start until the `payment-secrets` Secret from [task 09](09-containerization-and-deployment.md) exists.
