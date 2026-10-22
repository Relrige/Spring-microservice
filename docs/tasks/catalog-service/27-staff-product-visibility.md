---
status: TODO
service: catalog-service
---
# EP-CAT-04/05: Staff Product Visibility and Full Product Details

## Context
Staff prepare products while they are `NOT_ACTIVE`, but Catalog's read API only lets `CATALOG_MANAGER` see inactive products, and only in the list. EP-CAT-05 hides them from everyone, and its answer has no `status`. As a result the manager console cannot load an inactive product's description (frontend backlog BL-01), and the worker console cannot see inactive products' titles (BL-02).

This task applies one visibility rule to both read endpoints and makes EP-CAT-05 answer the full view.

Order across services: independent of gateway [task 12](../api-getaway/12-optional-product-details-route.md). Until that task is done, the gateway forwards no role on `GET /catalog/products/{id}`, so EP-CAT-05 behaves as before for real traffic. Either order is safe.

*References:* [Catalog spec — Product visibility, EP-CAT-04, EP-CAT-05, revision 2026-10-22 (D1, D2)](../../design/services-specs/catalog-service-spec.md), [API Contracts — Catalog](../../design/services-requirements/api-contracts.md#3-catalog-service), [OpenAPI Conventions](../../design/services-requirements/openapi-conventions.md), frontend [BL-01](../../../frontend/docs/backlog/BL-01-manager-reads-inactive-products.md), [BL-02](../../../frontend/docs/backlog/BL-02-worker-product-titles.md)

## Acceptance Criteria
- [ ] `ProductService.visibleStatus` treats `CATALOG_MANAGER` and `INVENTORY_WORKER` as staff: no status filter, and the requested `status` filter applies. Every other role (`CUSTOMER`, `ADMIN`) and anonymous callers get `ACTIVE` only, and `status` is ignored.
- [ ] `GET /catalog/products` (EP-CAT-04) with role `INVENTORY_WORKER` returns `ACTIVE` and `NOT_ACTIVE` products, and `?status=NOT_ACTIVE` returns only inactive ones.
- [ ] `GET /catalog/products/{id}` (EP-CAT-05) takes the caller from the authenticated principal, as EP-CAT-04 does, and uses the same rule:
  - staff → `200` for an active and for an inactive product;
  - anonymous, `CUSTOMER`, `ADMIN` → `200` for an active product, `404` for an inactive one, with the same problem body as for a missing product.
- [ ] EP-CAT-05 answers `ProductResponse` `{ productId, title, description, categoryId, basePrice, status, stockStatus }` for every caller. `ProductDetailsResponse` is deleted, and `getActiveProduct` is replaced by a role-aware method.
- [ ] OpenAPI: EP-CAT-05 carries `@OptionalAuthentication` (security `[{bearerAuth: []}, {}]`), and its description names the staff roles. EP-CAT-04's description says "CATALOG_MANAGER or INVENTORY_WORKER" instead of "CATALOG_MANAGER". `catalog-service/openapi/public.json` is regenerated (`mvnw.cmd test -Dtest=OpenApiDocumentTest -Dopenapi.update=true`) and committed.
- [ ] Tests:
  - unit tests for `visibleStatus` cover every role and anonymous;
  - `ProductControllerTest` checks the EP-CAT-05 JSON, including `status`;
  - the integration test (`CatalogReadAndStatusIntegrationTest` or a new one) covers EP-CAT-04 and EP-CAT-05 for anonymous, `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER` and `ADMIN`, with an active and an inactive product.
- [ ] The Catalog spec's implementation note for the 2026-10-22 revision names this task as implemented.

## Technical Notes / Constraints
- **Settled (D1):** staff = `CATALOG_MANAGER` and `INVENTORY_WORKER`, on both endpoints. `ADMIN` is not staff. Visibility is business logic in the service, not a Spring Security rule: `SecurityConfig` keeps `permitAll` for `GET /catalog/products/*`.
- **Settled (D2):** one response shape for everyone. Don't return a reduced shape for customers. A separate back-office path was rejected.
- A non-staff caller must not be able to tell an inactive product from a missing one: same status, same `detail`.
- The controller never reads `X-User-Role` itself; it uses `@AuthenticationPrincipal AuthenticatedUser`, as EP-CAT-04 does. An internal call (role `SERVICE`, no user) counts as non-staff.
- Removing `ProductDetailsResponse` changes the generated frontend types. That's expected, and the frontend regenerates them (BL-01).
