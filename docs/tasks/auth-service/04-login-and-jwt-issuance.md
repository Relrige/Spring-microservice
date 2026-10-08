---
status: TODO
service: auth-service
---
# EP-AUTH-02: Login and JWT Issuance

## Context
Any user (customer or staff) authenticates with email and password and receives a signed JWT. The API Gateway validates the token and forwards trusted `X-User-Id` and `X-User-Role` headers to downstream services, so the token's claims must contain everything the gateway needs.
*References:* [Auth Service Spec — EP-AUTH-02](../../design/services-specs/auth-service-spec.md), [Customer Scenarios (SCN-C03, SCN-C04)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [ ] `POST /auth/login` accepts `{ email, password }` and returns `200 OK { token }`.
- [ ] Missing or empty `email`/`password` returns `400 Bad Request`.
- [ ] Unknown email and wrong password both return an identical `401 Unauthorized` ("Invalid credentials") response, with no difference in body or status.
- [ ] Issued JWT is signed and contains `sub` (userId), `role`, `iat` and `exp`.
- [ ] Token lifetime is configurable via `application.yml` (property, not a hard-coded constant).
- [ ] Signing key is read from configuration/secret (env variable), never committed to the repository.
- [ ] Tests: successful login; token claims decoded and asserted; unknown email; wrong password; error responses are indistinguishable; an expired or tampered token is rejected by the verifier used in tests.

## Technical Notes / Constraints
- **Open decisions (record in docs before or during implementation):**
  - Algorithm: symmetric HS256 (secret shared with the gateway) vs. asymmetric RS256/ES256 (gateway only needs the public key; better isolation, optionally exposed via a JWKS endpoint). Present trade-offs and decide.
  - Token lifetime (the spec says "TBD"). Refresh tokens are out of scope.
- JWT library options: `jjwt`, Nimbus JOSE (`spring-security-oauth2-jose`). Pick one and keep it behind a small `TokenService` interface so it is easy to test.
- User enumeration defence: avoid the timing difference between "user not found" and "wrong password" (e.g., run a BCrypt comparison against a dummy hash when the user is missing).
- Never log passwords or tokens.
