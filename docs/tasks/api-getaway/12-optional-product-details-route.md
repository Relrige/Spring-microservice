---
status: DONE
service: api-gateway
---
# Route `GET /catalog/products/{id}` as `Optional`

## Context
Catalog's EP-CAT-05 now shows inactive products to staff (Catalog decision D1, [Catalog task 27](../catalog-service/27-staff-product-visibility.md)). It can only do that if it learns the caller's role. The route `catalog-get-product` is `Public`, so the gateway forwards every request as anonymous. Switching it to `Optional` (D3) forwards the role when a valid token is sent, the same as `catalog-browse-products`.

Independent of Catalog task 27: before it, Catalog ignores the role on this path; after it, the role takes effect. Either order is safe.

*References:* [API Gateway spec — Request Pipeline (auth modes), Route Table](../../design/services-specs/api-gateway-spec.md), [Catalog spec — Product visibility, revision 2026-10-22 (D3)](../../design/services-specs/catalog-service-spec.md), frontend [BL-01](../../../frontend/docs/backlog/BL-01-manager-reads-inactive-products.md)

## Acceptance Criteria
- [x] In `api-gateway/src/main/resources/application.yml`, route `catalog-get-product` has `authMode: OPTIONAL`, with a comment in the style of `catalog-browse-products` (staff see inactive products; a stale token is a `401`). The UUID constraint on `{id}` stays.
- [x] `RouteTableTest` asserts `catalog-get-product` → `RouteAccess(AuthMode.OPTIONAL, Set.of())`; the route count is unchanged.
- [x] Tests (routing/WireMock test of the gateway):
  - without a token: forwarded as anonymous, with no `X-User-*` headers;
  - with a valid `CATALOG_MANAGER` token: forwarded with `X-User-Id` and `X-User-Role: CATALOG_MANAGER`;
  - with an invalid or expired token: `401`, upstream not called;
  - client-supplied `X-User-Role` is still stripped.
- [x] The gateway spec's route table already says `Optional` (updated 2026-10-22); check that it matches.

## Technical Notes / Constraints
- **Settled (D3):** `Optional`, not `Required`. Customers and anonymous visitors must keep reading product details without a token.
- Behaviour change to keep in mind: an invalid token on this path is now `401` instead of being silently ignored. The storefront sends no token on catalog reads, so it is unaffected.
- `GET /catalog/products/batch-get` must stay unrouted. The UUID regex on `{id}` already guarantees that; don't loosen it.

## Implementation Notes (2026-10-22)
- `application.yml`: route `catalog-get-product` now has `authMode: OPTIONAL`, with a comment in the style of `catalog-browse-products`. The UUID regex on `{id}` is unchanged, so `GET /catalog/products/batch-get` stays unrouted. No Java code changed: the auth mode is read from route metadata by `RouteAccessPolicy`.
- **Verified** (full suite `mvnw.cmd clean test`: 377 tests, all passed):
  - `RouteTableTest.accessRulesAreParsedFromMetadata`: `catalog-get-product` → `RouteAccess(OPTIONAL, Set.of())`; the route count stays 31.
  - `TrustBoundaryFilterTest`, new section for `GET /catalog/products/{id}`:
    - without a token → forwarded anonymously, no `X-User-*` headers;
    - `CATALOG_MANAGER` token → `X-User-Id` and `X-User-Role: CATALOG_MANAGER`, no `Authorization` header;
    - garbage, expired, Basic and empty Bearer credentials → `401`, upstream not called;
    - forged `X-User-Id`/`X-User-Role`/`X-User-Email`/`X-Internal-Token` are stripped without a token, and replaced by the token's identity with one.
  - The route-generated matrices (`GatewaySecuritySuiteTest`, role matrix and invalid-token checks) now treat the route as `Optional` and pass unchanged.
- The gateway spec's route table already said `Optional` (updated 2026-10-22); it matches.

## Deviations from spec / task
None.

*Spec sync (2026-10-22):* the API Gateway spec already matched; the Catalog spec's revision note no longer lists this task as open.
