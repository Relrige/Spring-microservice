---
status: DONE
service: auth-service
---
# Trusted-Header Authentication with Spring Security (Pilot)

## Context
The API Gateway validates the JWT and forwards trusted `X-User-Id` and `X-User-Role` headers; services do not validate tokens. Today auth-service enforces the admin-only endpoint with a hand-written `AdminOnlyInterceptor`. Other services will need the same role-based protection, and some endpoints are `Internal` (called service-to-service, bypassing the gateway, so they carry no user headers).

This task replaces the interceptor with Spring Security, authenticating **solely from trusted headers**, and uses auth-service as the pilot. The resulting small `security` package is copied into the other services later (copy per service, no shared library for now).

Decisions already made:
- Spring Security with pre-authenticated (trusted header) users; role rules declared per route in the security filter chain.
- Internal calls are authenticated with a shared internal token (`X-Internal-Token`), checked by a filter on the receiver and added by an interceptor on the sender (see the order-service follow-up task).
- Role names are the full names: `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN`.

*References:* [Bootstrap Staff and Admin Accounts](06-privileged-user-bootstrap.md), [API Gateway Spec](../../design/services-specs/api-gateway-spec.md), [API Contracts](../../design/services-requirements/api-contracts.md), [Order Service follow-up](../order-service/internal-token-sender-and-security.md)

## Acceptance Criteria
- [x] Re-add `spring-boot-starter-security` and `spring-boot-starter-security-test`.
- [x] Create `AuthenticatedUser` record (`UUID id`, role) used as the authentication principal; the granted authority is `ROLE_<role>`.
- [x] Create `TrustedHeaderAuthenticationFilter`: when both `X-User-Id` (valid UUID) and `X-User-Role` (known role) are present, put an authenticated `AuthenticatedUser` into the `SecurityContext`. Missing or malformed headers leave the request anonymous (never an error inside the filter).
- [x] Create `InternalTokenAuthenticationFilter`: when `X-Internal-Token` equals the configured token (constant-time comparison), authenticate as a service principal with authority `ROLE_SERVICE`. A wrong or absent token leaves the request anonymous. A valid internal token takes precedence over user headers.
- [x] Configure a stateless `SecurityFilterChain`: CSRF, form login, HTTP Basic and sessions disabled; both filters registered; `POST /auth/login`, `POST /user/register` and health endpoints are `permitAll`; `anyRequest().denyAll()` (fail closed: every route needs an explicit rule).
- [x] Protect `POST /user/non-customer` with a route rule `hasRole("ADMIN")` in the filter chain and delete `AdminOnlyInterceptor` and `WebConfig`.
- [x] Unauthenticated requests to protected endpoints return `401`, and authenticated requests without the needed role return `403`. Both use the existing `ProblemDetail` format (type, title, detail, timestamp, service) through a custom `AuthenticationEntryPoint` and `AccessDeniedHandler`. The `401` detail is fixed ("Authentication required").
- [x] Add `auth.internal.token` configuration (`INTERNAL_TOKEN` env variable, validated: not blank, at least 32 characters), with examples in `.env.example` and test properties. The token is never logged.
- [x] Tests:
  - Unit tests for both filters (valid, missing, malformed, wrong token, precedence).
  - Controller/slice tests with the security configuration imported: `403` for missing header, for `CUSTOMER` and for `CATALOG_MANAGER`; `201` for `ADMIN`; `401` for anonymous access to a protected endpoint; public endpoints stay open without any headers.
  - A test endpoint or slice test proving `hasRole('SERVICE')` accepts only a valid internal token.
  - Existing integration tests (non-customer creation, seeder login) still pass using the new header mechanism.
- [x] Documentation: record the decision ([Security Model](../../design/services-requirements/security-model.md)) (trusted headers, internal token, copy per service, full role names) in `docs/`; fix role names in `api-contracts.md` (`CATALOG_MGR` → `CATALOG_MANAGER`, `INVENTORY_W` → `INVENTORY_WORKER`); state in the gateway draft that the gateway must strip client-supplied `X-User-Id`, `X-User-Role` and `X-Internal-Token` headers.

## Technical Notes / Constraints
- **Role rules live in one place:** each role-protected route has a rule in `SecurityConfig` (`requestMatchers(...).hasRole(...)`). A caller with the wrong role is rejected in the filter chain with `403`, before the controller is reached and before the body is bound or validated (no `400` validation leak). Method security (`@PreAuthorize`) was deliberately not used: it would run only after request binding and validation, and would need extra handling for `AccessDeniedException` in the MVC exception advice.
- Filters must not throw for bad input; they only decide whether to authenticate. Rejection happens in the chain's authorization rules, which keeps a single place for `401`/`403` rendering.
- Compare the internal token with `MessageDigest.isEqual` on bytes to avoid timing leaks.
- Trusting headers is only safe if services are unreachable except through the gateway and internal callers. Task 07 must cover this (ClusterIP services, `NetworkPolicy`, explicit gateway route allowlist that never exposes `Internal` endpoints).
- Keep the `security` package self-contained (no dependency on auth-service entities beyond a role enum that is trivial to copy), because it will be copied into other services.
- Jackson 3 (`tools.jackson`) is used by Spring Boot 4; render the error responses with the same mechanism the advice uses, or write the `ProblemDetail` through Spring's message converters.
- **Implementation notes:** the filters are created inside `SecurityConfig` rather than declared as beans, otherwise Spring Boot would also register them as plain servlet filters. `UserDetailsServiceAutoConfiguration` is excluded in `application.yml` because there is no username/password login (it would create an unused default user and log its password). The chain ends with `anyRequest().denyAll()`, so a new route is unreachable (even for an administrator) until a rule is added. `RouteSecurityRulesTest` enforces this by calling every application route as an administrator and failing on any `403`; if a route is intentionally not reachable by administrators (e.g. internal-only), exclude it in that test and cover it with the right caller. Services with public endpoints (e.g. the catalog) must permit those explicitly.
