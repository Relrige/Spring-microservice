---
status: DONE
service: api-gateway
---
# Request Pipeline Filters (Strip, Authenticate, Authorize, Propagate)

## Context
This is the core of the gateway: the ordered filters that make the trusted-header model safe. Downstream services trust `X-User-Id` and `X-User-Role` blindly, so these headers must come only from a verified JWT.
*References:* [API Gateway Spec — Request Pipeline](../../design/services-specs/api-gateway-spec.md#request-pipeline), [Security Model](../../design/services-requirements/security-model.md), [JWT Verification](02-jwt-verification.md), [Route Allowlist](03-route-table.md)

## Acceptance Criteria
- [x] **Strip:** `X-User-Id`, `X-User-Role` and `X-Internal-Token` are removed from every incoming request before anything else runs (case-insensitive, all values).
- [x] **Authenticate** according to the route's auth mode:
  - `Public`: no token, valid token or invalid token → forwarded as anonymous (token ignored).
  - `Optional`: no token → anonymous; valid token → identity set; invalid, expired or malformed token → `401`.
  - `Required`: no token → `401`; valid token → identity set; invalid → `401`.
- [x] **Authorize:** if the route lists allowed roles and the caller's role is not among them → `403`. Roles are not hierarchical (`ADMIN` gets no implicit access).
- [x] **Propagate:** authenticated callers get `X-User-Id` (= `sub`) and `X-User-Role` (= `role`); anonymous requests carry neither.
- [x] **Remove `Authorization`** from the forwarded request.
- [x] A test proves that client-supplied `X-User-Id`, `X-User-Role` and `X-Internal-Token` never reach the upstream, in all three auth modes, also when combined with a valid token of a different user.
- [x] `401` and `403` are produced before the upstream is called (the upstream receives no request).
- [x] A role-matrix test covers allowed and disallowed roles for every protected route (parameterized from the route configuration).

## Technical Notes / Constraints
- Implement as filters attached to every route and driven by the route metadata from task 03, not as per-route ad-hoc code.
- Public routes ignore a bad token on purpose (e.g. an expired token must not break `POST /auth/login`).
- A `401` must not say why the token was rejected (expired, bad signature, malformed). The reason may be logged at debug level, never the token itself.
- Optional routes (`POST /orders`, `GET /catalog/products`) are deliberately strict: a client with a stale token gets `401` rather than silently becoming a guest. Tests should document this.

## Progress Notes (2026-10-14)
- **Decision — one route filter, declared in YAML and enforced at startup.** Gateway MVC has no global "default filters" for YAML routes and its YAML filters are static factory methods. `TrustBoundaryFilterFunctions.trustBoundary()` (registered through a `SimpleFilterSupplier` bean) looks up the `TrustBoundaryFilter` bean per request, the same way the framework's own filters reach beans. Every route lists `filters: - TrustBoundary`; `RouteAccessPolicy` refuses to start if a route omits it, so the pipeline cannot be forgotten.
- The filter runs after route matching (the route id is set by the framework's outermost route filter), so unmatched requests are `404` and never reach it. Strip, authenticate, authorize and propagate all happen in one place and build the forwarded request with `ServerRequest.from(request).headers(...)`, the mechanism of the built-in `RemoveRequestHeader` filter.
- `Authorization` handling: no header → anonymous; anything that is not exactly one `Bearer <token>` (case-insensitive scheme) counts as an invalid token. Rejection reasons are logged at debug level by the verifier; the token is never logged.
- 401/403 bodies come from `GatewayProblems` (problem+json, fixed details), shared with [task 05](05-gateway-error-handling.md).
- Tests: `TrustBoundaryFilterTest` (spoofed headers incl. lower-case duplicates in all three modes, with and without a token of another user; Public/Optional/Required token cases; role matrix generated from the route configuration via `RouteSamples`, 401 for anonymous) and `RouteAccessPolicyTest` (fail-fast rules).
