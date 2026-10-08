---
status: DONE
service: api-gateway
---
# JWT Verification Component

## Context
The gateway validates the user's JWT locally and never calls Auth Service. This task builds the isolated component that turns a bearer token into a verified identity (or a failure), so the request filters in [task 04](04-request-pipeline-filters.md) only deal with a simple result.
*References:* [API Gateway Spec — Request Pipeline](../../design/services-specs/api-gateway-spec.md#request-pipeline), [Auth Service Spec — JWT](../../design/services-specs/auth-service-spec.md#jwt), [Security Model](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [x] Add `gateway.jwt.secret` configuration (`JWT_SECRET` env variable), validated at startup: not blank, at least 32 characters. The secret is never logged.
- [x] Create a `JwtVerifier` (interface plus HS256 implementation) returning a verified identity (`UUID userId`, role) or an "invalid" outcome.
- [x] A token is valid only when: the HS256 signature verifies with `JWT_SECRET`; `exp` is in the future, with a small configurable clock-skew leeway; `sub` is a valid UUID; `role` is one of `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN`.
- [x] Reject tokens with another algorithm (including `none`), a missing `exp`, a missing or malformed `sub` or `role`, a malformed structure, and a bad signature.
- [x] Unit tests cover each valid and invalid case above, including a token signed with a different secret and an expired token inside and outside the leeway. Test tokens carry the same claims as Auth Service tokens.

## Technical Notes / Constraints
- The verifier never throws for bad input; it reports failure. The filter decides what a failure means for each auth mode.
- Copy the role enum (trivial) rather than depending on another service; keep this package self-contained, in the spirit of the "copy per service" decision.
- Isolating verification behind an interface keeps the move to an asymmetric algorithm (a listed future improvement) a local change.
- Compatibility with tokens from a real auth-service instance is verified in [task 09](09-end-to-end-verification.md).
