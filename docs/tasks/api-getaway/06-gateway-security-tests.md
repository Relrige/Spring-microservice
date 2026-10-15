---
status: DONE
service: api-gateway
---
# Gateway Security and Routing Test Suite

## Context
The gateway is the one component whose bugs silently turn into security holes in every other service. Earlier tasks have their own unit tests; this task adds the cross-cutting suite that proves the trust boundary as a whole.
*References:* [API Gateway Spec — Trust Boundary](../../design/services-specs/api-gateway-spec.md#trust-boundary), [Security Model — Preconditions](../../design/services-requirements/security-model.md#preconditions-otherwise-the-model-is-unsafe)

## Acceptance Criteria
- [x] Integration tests run the full application against a stub upstream (e.g. WireMock or MockWebServer) that records the request it receives.
- [x] Route-coverage test: every row of the spec's route table is reachable by an allowed caller; every internal endpoint listed in the spec is `404`; a route without an auth mode fails the test.
- [x] Spoofing test: client-supplied `X-User-Id`, `X-User-Role` and `X-Internal-Token` never reach the upstream, for each auth mode.
- [x] The upstream never receives `Authorization`.
- [x] Role-matrix test: for every protected route, each role in `{CUSTOMER, CATALOG_MANAGER, INVENTORY_WORKER, ADMIN}` is either passed through or gets `403` per the table; anonymous gets `401`.
- [x] Token test matrix on a `Required` and an `Optional` route: missing, expired, bad signature, wrong algorithm, malformed, unknown role, non-UUID subject.
- [x] Body, query string (e.g. `?token=` for guest orders), method and path reach the upstream unchanged.
- [x] The suite runs with `mvn test` without Docker or any other service.

## Technical Notes / Constraints
- Parameterize tests from the route configuration where possible, so the suite grows with the table instead of being copied by hand.
- This is the gateway counterpart of `RouteSecurityRulesTest` in auth-service; follow the same fail-closed spirit.

## Progress Notes (2026-10-14)
- **Stub upstream (decision):** the JDK's built-in `com.sun.net.httpserver.HttpServer` (`StubUpstream`) instead of WireMock/MockWebServer: no extra dependency, records method, URI, body and all header values, and can reply with any status/body or a delay. `GatewayIntegrationTest` starts one stub per upstream once per JVM, so all integration test classes share one Spring context.
- Suite (`mvn test`, no Docker; 214 tests in total):
  - `GatewaySecuritySuiteTest`: configured routes equal the implemented rows of the spec table (a new route must be added to the checklist); every route reachable by an allowed caller with forged `X-User-*`/`X-Internal-Token` replaced or removed and no `Authorization` upstream; token matrix (expired, bad signature, `none`, HS512, malformed, unknown role, non-UUID subject, missing `exp`) on a Required and an Optional route; method, path, query (`?token=`, repeated and encoded parameters) and UTF-8 body unchanged.
  - Role matrix and per-mode spoofing tests: `TrustBoundaryFilterTest` ([task 04](04-request-pipeline-filters.md)); internal endpoints and non-UUID ids: `RouteTableTest` ([task 03](03-route-table.md)); "route without an auth mode fails": `RouteAccessPolicyTest`.
- Observation: the forwarded `Content-Type: application/json` gains `;charset=UTF-8` (servlet stack re-renders the media type). Body bytes are unchanged; the services are unaffected.
