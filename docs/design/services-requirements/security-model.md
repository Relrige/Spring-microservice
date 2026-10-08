# Security Model — Trusted Headers and Internal Token

> Decision record for how services identify callers. Pilot implementation: auth-service ([task 08](../../tasks/auth-service/08-trusted-header-authentication.md)). The gateway side is specified in the [API Gateway Spec](../services-specs/api-gateway-spec.md).

## Decisions

1. **Gateway authenticates, services trust.** The API Gateway validates the JWT and forwards `X-User-Id` and `X-User-Role`. Services never validate tokens.
2. **Spring Security on every service, driven only by those headers.** A filter turns the headers into a Spring `Authentication` (principal `AuthenticatedUser(id, role)`, authority `ROLE_<role>`). Role rules are declared per route in the security filter chain (`requestMatchers(...).hasRole(...)`), in one place per service. The filter chain is stateless (no sessions, CSRF, login form or HTTP Basic).
3. **Internal service-to-service calls use a shared internal token.** Callers add `X-Internal-Token` to outgoing requests (client-side interceptor); receivers authenticate it with a second filter and grant `ROLE_SERVICE`. `Internal` endpoints require `hasRole('SERVICE')`. User headers are not forwarded on internal calls.
4. **Code is copied per service**, not shared through a library. The `security` package is kept self-contained so copies stay identical.
5. **Role names are the full names** used everywhere (API contracts, JWT claim, headers, enums): `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN`.

## Behaviour of the filters

| Request | Result |
|---|---|
| Valid `X-User-Id` (UUID) and known `X-User-Role` | Authenticated user |
| Missing, partial, malformed headers or unknown role | Anonymous (never an error inside the filter) |
| Valid `X-Internal-Token` | Authenticated service (`ROLE_SERVICE`); takes precedence over user headers |
| Wrong or empty `X-Internal-Token` | Anonymous |

Authorization then decides: anonymous on a protected endpoint is `401`, authenticated without the required role is `403`. Public endpoints (e.g. the public catalog) are explicitly permitted in each service's filter chain. The chain ends with `denyAll()`: a route without an explicit rule is unreachable for everyone, and a test per service verifies that every route has a rule.

## Preconditions (otherwise the model is unsafe)

- Services must not be reachable by clients except through the gateway (ClusterIP services, `NetworkPolicy`).
- The gateway must strip client-supplied `X-User-Id`, `X-User-Role` and `X-Internal-Token` headers, and must never route `Internal` endpoints.
- `INTERNAL_TOKEN` is the same secret in every service, at least 32 characters, supplied from a Kubernetes Secret and never logged.

## Known trade-offs

- Role rules are URL-based, so they must be updated when a route is added or renamed. A forgotten rule fails closed (`denyAll()`) and a route-coverage test catches it. Method security (`@PreAuthorize`) was rejected: it runs after request binding and validation, so an unauthorized caller could still receive validation feedback.
- A single shared internal token identifies "some VoltStore service", not which one. Per-service identities (service JWTs or mTLS) would be a later improvement.
