---
status: TODO
service: api-gateway
---
# Request Pipeline Filters (Strip, Authenticate, Authorize, Propagate)

## Context
This is the core of the gateway: the ordered filters that make the trusted-header model safe. Downstream services trust `X-User-Id` and `X-User-Role` blindly, so these headers must come only from a verified JWT.
*References:* [API Gateway Spec — Request Pipeline](../../design/services-specs/api-gateway-spec.md#request-pipeline), [Security Model](../../design/services-requirements/security-model.md), [JWT Verification](02-jwt-verification.md), [Route Allowlist](03-route-table.md)

## Acceptance Criteria
- [ ] **Strip:** `X-User-Id`, `X-User-Role` and `X-Internal-Token` are removed from every incoming request before anything else runs (case-insensitive, all values).
- [ ] **Authenticate** according to the route's auth mode:
  - `Public`: no token, valid token or invalid token → forwarded as anonymous (token ignored).
  - `Optional`: no token → anonymous; valid token → identity set; invalid, expired or malformed token → `401`.
  - `Required`: no token → `401`; valid token → identity set; invalid → `401`.
- [ ] **Authorize:** if the route lists allowed roles and the caller's role is not among them → `403`. Roles are not hierarchical (`ADMIN` gets no implicit access).
- [ ] **Propagate:** authenticated callers get `X-User-Id` (= `sub`) and `X-User-Role` (= `role`); anonymous requests carry neither.
- [ ] **Remove `Authorization`** from the forwarded request.
- [ ] A test proves that client-supplied `X-User-Id`, `X-User-Role` and `X-Internal-Token` never reach the upstream, in all three auth modes, also when combined with a valid token of a different user.
- [ ] `401` and `403` are produced before the upstream is called (the upstream receives no request).
- [ ] A role-matrix test covers allowed and disallowed roles for every protected route (parameterized from the route configuration).

## Technical Notes / Constraints
- Implement as filters attached to every route and driven by the route metadata from task 03, not as per-route ad-hoc code.
- Public routes ignore a bad token on purpose (e.g. an expired token must not break `POST /auth/login`).
- A `401` must not say why the token was rejected (expired, bad signature, malformed). The reason may be logged at debug level, never the token itself.
- Optional routes (`POST /orders`, `GET /catalog/products`) are deliberately strict: a client with a stale token gets `401` rather than silently becoming a guest. Tests should document this.
