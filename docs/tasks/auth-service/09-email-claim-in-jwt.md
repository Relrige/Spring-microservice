---
status: TODO
service: auth-service
---
# Email Claim in the JWT

## Context
Only registered customers can order (decision 2026-10-16), and Notification Service sends every order email to the account's address. Order Service gets that address from the gateway header `X-User-Email`, which the gateway takes from a new `email` claim in the JWT ([Order spec — decision D1](../../design/services-specs/order-service-spec.md)). This task adds the claim; the gateway side is [API Gateway task 11](../api-getaway/11-forward-user-email-header.md).
*References:* [Auth Spec — JWT](../../design/services-specs/auth-service-spec.md#jwt), [Security Model](../../design/services-requirements/security-model.md), [Login task](04-login-and-jwt-issuance.md)

## Acceptance Criteria
- [ ] `JwtTokenService` adds the claim `email` with the user's **normalized** email (trimmed, lower-cased, as stored) to every issued token, for every role.
- [ ] The `TokenService` interface documents the new claim; the other claims (`sub`, `role`, `iat`, `exp`) are unchanged.
- [ ] Tests: a login token contains `email` equal to the stored, normalized email (also when the user logged in with different letter case); existing token tests still pass.
- [ ] The Auth spec's JWT table already lists the claim (updated 2026-10-16); check it matches the code.

## Technical Notes / Constraints
- The token is signed, not encrypted: anyone holding it can read the email. That is acceptable (it is the user's own token), but the email must not be logged together with tokens.
- Tokens issued before the change have no `email` claim and stay valid until they expire (default 1 h); the gateway handles the missing claim. No migration is needed.
- Do not add other personal data to the token (no names, no phone): the JWT is an identity token, not a profile.
