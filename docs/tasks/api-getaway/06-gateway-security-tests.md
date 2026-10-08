---
status: TODO
service: api-gateway
---
# Gateway Security and Routing Test Suite

## Context
The gateway is the one component whose bugs silently turn into security holes in every other service. Earlier tasks have their own unit tests; this task adds the cross-cutting suite that proves the trust boundary as a whole.
*References:* [API Gateway Spec — Trust Boundary](../../design/services-specs/api-gateway-spec.md#trust-boundary), [Security Model — Preconditions](../../design/services-requirements/security-model.md#preconditions-otherwise-the-model-is-unsafe)

## Acceptance Criteria
- [ ] Integration tests run the full application against a stub upstream (e.g. WireMock or MockWebServer) that records the request it receives.
- [ ] Route-coverage test: every row of the spec's route table is reachable by an allowed caller; every internal endpoint listed in the spec is `404`; a route without an auth mode fails the test.
- [ ] Spoofing test: client-supplied `X-User-Id`, `X-User-Role` and `X-Internal-Token` never reach the upstream, for each auth mode.
- [ ] The upstream never receives `Authorization`.
- [ ] Role-matrix test: for every protected route, each role in `{CUSTOMER, CATALOG_MANAGER, INVENTORY_WORKER, ADMIN}` is either passed through or gets `403` per the table; anonymous gets `401`.
- [ ] Token test matrix on a `Required` and an `Optional` route: missing, expired, bad signature, wrong algorithm, malformed, unknown role, non-UUID subject.
- [ ] Body, query string (e.g. `?token=` for guest orders), method and path reach the upstream unchanged.
- [ ] The suite runs with `mvn test` without Docker or any other service.

## Technical Notes / Constraints
- Parameterize tests from the route configuration where possible, so the suite grows with the table instead of being copied by hand.
- This is the gateway counterpart of `RouteSecurityRulesTest` in auth-service; follow the same fail-closed spirit.
