---
status: TODO
service: api-gateway
---
# End-to-End Verification with Auth Service

## Context
For now only Auth Service is implemented behind the gateway. This task proves the real contract between the two: tokens issued by Auth Service verify in the gateway, and the trusted headers drive Auth Service's own security.
*References:* [API Gateway Spec — Auth Service Interaction](../../design/services-specs/api-gateway-spec.md#auth-service-interaction), [Auth Service Spec](../../design/services-specs/auth-service-spec.md)

## Acceptance Criteria
- [ ] With docker-compose (gateway + auth-service + its database), and through the gateway only: `POST /user/register` → `201`; duplicate email → `409` (passed through); `POST /auth/login` → `200` with a token; wrong password → `401` "Invalid credentials" (from Auth Service, not the gateway).
- [ ] `POST /user/non-customer`: no token → `401` (gateway); customer token → `403` (gateway); seeded admin token → `201`; expired or garbled token → `401`.
- [ ] A forged `X-User-Role: ADMIN` header sent with a customer token, or without any token, to `POST /user/non-customer` does not elevate privileges.
- [ ] `/actuator/...` and any other unrouted path through the gateway return `404`.
- [ ] Update the API test collections (`VoltStore_API_Tests*.json`) or add a request set for the gateway, and record the verified steps in the task notes.
- [ ] Update `docs/` where reality diverged from the spec (e.g. the WebMVC variant and route-metadata decisions).

## Technical Notes / Constraints
- `JWT_SECRET` in the gateway and auth-service `.env` files must match.
- Customer, catalog, order, inventory and delivery routes cannot be verified end-to-end yet; they are covered by the stub-upstream tests in [task 06](06-gateway-security-tests.md). Repeat the route checks per service as each one is implemented.
