---
status: DONE
service: api-gateway
---
# Forward the Account Email as `X-User-Email`

## Context
Order Service needs the customer's account email for the order events (decision D1 in the [Order spec](../../design/services-specs/order-service-spec.md)). Auth puts it into the JWT as the `email` claim ([Auth task 09](../auth-service/09-email-claim-in-jwt.md)); the gateway forwards it like the other identity headers. Because it is an identity header, a client must not be able to set it.
*References:* [API Gateway Spec — request pipeline](../../design/services-specs/api-gateway-spec.md), [Security Model](../../design/services-requirements/security-model.md), [Request pipeline task](04-request-pipeline-filters.md), [Trust boundary task](08-ingress-and-trust-boundary.md)

## Acceptance Criteria
- [x] Pipeline step 1 also strips a client-supplied `X-User-Email` (any letter case), together with `X-User-Id`, `X-User-Role` and `X-Internal-Token`.
- [x] Pipeline step 5 sets `X-User-Email` from the `email` claim for an authenticated caller. A valid token **without** the claim (issued before Auth task 09) is still accepted; the header is then simply not set.
- [x] Anonymous requests carry no `X-User-Email`.
- [x] Tests: a forged `X-User-Email` from the client never reaches the upstream (with and without a token); a token with the claim → the upstream sees the claim's value; a token without the claim → no header, request still forwarded.
- [x] The trust-boundary checks of task 08/09 are extended with the new header.

## Technical Notes / Constraints
- No authorization rule may depend on `X-User-Email`; it is data for the service, not a credential.
- Header values from the token must not be logged (the email is personal data).

## Implementation (2026-10-17)
- `HmacJwtVerifier` reads the `email` claim into `VerifiedIdentity.email()` (an `Optional`). A missing, blank or non-string claim is ignored, not rejected: the claim is data, not a credential, and older tokens stay valid.
- `TrustBoundaryFilter` strips `X-User-Email` with the other identity headers (step 1) and sets it only when the token has the claim (step 5). No rule reads it, and it is not logged.
- Tests: `HmacJwtVerifierTest` (claim present; missing, blank, numeric); `TrustBoundaryFilterTest` (forged header in two letter cases never reaches the upstream, with and without a token; token with the claim → the claim's value; token without it → no header, request still forwarded; Public route forwards no email); `GatewaySecuritySuiteTest` sends a forged `X-User-Email` on every route. All 288 gateway tests pass.
- End to end (Compose, 2026-10-17): Order accepted `POST /orders` with the header set by the gateway, and a forged `X-User-Email` without a token was a `401`. Neither the gateway nor Order logged the email.
