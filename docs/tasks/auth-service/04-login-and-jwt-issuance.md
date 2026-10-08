---
status: DONE
service: auth-service
---
# EP-AUTH-02: Login and JWT Issuance

## Context
Any user (customer or staff) authenticates with email and password and receives a signed JWT. The API Gateway validates the token and forwards trusted `X-User-Id` and `X-User-Role` headers to downstream services, so the token's claims must contain everything the gateway needs.
*References:* [Auth Service Spec — EP-AUTH-02](../../design/services-specs/auth-service-spec.md), [Customer Scenarios (SCN-C03, SCN-C04)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [x] `POST /auth/login` accepts `{ email, password }` and returns `200 OK { token }`.
- [x] Missing or empty `email`/`password` returns `400 Bad Request`.
- [x] Unknown email and wrong password both return an identical `401 Unauthorized` ("Invalid credentials") response, with no difference in body or status.
- [x] Issued JWT is signed and contains `sub` (userId), `role`, `iat` and `exp`.
- [x] Token lifetime is configurable via `application.yml` (property, not a hard-coded constant).
- [x] Signing key is read from configuration/secret (env variable), never committed to the repository.
- [x] Tests: successful login; token claims decoded and asserted; unknown email; wrong password; error responses are indistinguishable; an expired or tampered token is rejected by the verifier used in tests.

## Technical Notes / Constraints
- **Decisions:**
  - Algorithm: symmetric **HS256**. The API Gateway shares the same secret (`JWT_SECRET`, at least 32 bytes) to validate tokens. Asymmetric RS256/ES256 would let the gateway hold only a public key, but it needs key-pair management and a JWKS endpoint; this can replace HS256 later without changing the API, because signing is hidden behind `TokenService`.
  - Token lifetime: 1 hour (`auth.jwt.expiration`). Refresh tokens are out of scope.
  - Library: Nimbus JOSE + JWT (`nimbus-jose-jwt`, version pinned in `pom.xml` because Spring Boot does not manage it), behind the `TokenService` interface (`JwtTokenService` implementation).
- User enumeration defence: when the email is unknown, the password is still checked against a dummy BCrypt hash so both failure paths take similar time. Both return the same `401` body.
- Email is normalized (trim + lowercase) via `EmailNormalizer`, shared with registration.
- Never log passwords or tokens.
