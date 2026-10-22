---
status: DONE
service: catalog-service
---
# EP-CAT-04/05: Staff Product Visibility and Full Product Details

## Context
Staff prepare products while they are `NOT_ACTIVE`, but Catalog's read API only lets `CATALOG_MANAGER` see inactive products, and only in the list. EP-CAT-05 hides them from everyone, and its answer has no `status`. As a result the manager console cannot load an inactive product's description (frontend backlog BL-01), and the worker console cannot see inactive products' titles (BL-02).

This task applies one visibility rule to both read endpoints and makes EP-CAT-05 answer the full view.

Order across services: independent of gateway [task 12](../api-getaway/12-optional-product-details-route.md). Until that task is done, the gateway forwards no role on `GET /catalog/products/{id}`, so EP-CAT-05 behaves as before for real traffic. Either order is safe.

*References:* [Catalog spec — Product visibility, EP-CAT-04, EP-CAT-05, revision 2026-10-22 (D1, D2)](../../design/services-specs/catalog-service-spec.md), [API Contracts — Catalog](../../design/services-requirements/api-contracts.md#3-catalog-service), [OpenAPI Conventions](../../design/services-requirements/openapi-conventions.md), frontend [BL-01](../../../frontend/docs/backlog/BL-01-manager-reads-inactive-products.md), [BL-02](../../../frontend/docs/backlog/BL-02-worker-product-titles.md)

## Acceptance Criteria
- [x] `ProductService.visibleStatus` treats `CATALOG_MANAGER` and `INVENTORY_WORKER` as staff: no status filter, and the requested `status` filter applies. Every other role (`CUSTOMER`, `ADMIN`) and anonymous callers get `ACTIVE` only, and `status` is ignored.
- [x] `GET /catalog/products` (EP-CAT-04) with role `INVENTORY_WORKER` returns `ACTIVE` and `NOT_ACTIVE` products, and `?status=NOT_ACTIVE` returns only inactive ones.
- [x] `GET /catalog/products/{id}` (EP-CAT-05) takes the caller from the authenticated principal, as EP-CAT-04 does, and uses the same rule:
  - staff → `200` for an active and for an inactive product;
  - anonymous, `CUSTOMER`, `ADMIN` → `200` for an active product, `404` for an inactive one, with the same problem body as for a missing product.
- [x] EP-CAT-05 answers `ProductResponse` `{ productId, title, description, categoryId, basePrice, status, stockStatus }` for every caller. `ProductDetailsResponse` is deleted, and `getActiveProduct` is replaced by a role-aware method.
- [x] OpenAPI: EP-CAT-05 carries `@OptionalAuthentication` (security `[{bearerAuth: []}, {}]`), and its description names the staff roles. EP-CAT-04's description says "CATALOG_MANAGER or INVENTORY_WORKER" instead of "CATALOG_MANAGER". `catalog-service/openapi/public.json` is regenerated (`mvnw.cmd test -Dtest=OpenApiDocumentTest -Dopenapi.update=true`) and committed.
- [x] Tests:
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

## Decisions (2026-10-22)
Settled before implementation (autopilot run for Catalog tasks 27–29; the user accepted every recommended answer):
- Work continues on the branch that holds the design commit; this run covers Catalog only, and gateway task 12 and Inventory tasks 22–24 follow in their own runs.
- The last acceptance criterion (the spec's implementation note) is done in the spec sync that ends this run, not in this task's commit.

## Implementation Notes (2026-10-22)
- **Visibility rule.** `ProductService.visibleStatus` now asks `isStaff(role)` (`CATALOG_MANAGER` or `INVENTORY_WORKER`) instead of comparing with `CATALOG_MANAGER`. Staff get the requested `status` (or no filter); everyone else, including an internal call without a user (`null` role), gets `ACTIVE`.
- **EP-CAT-05.** `getActiveProduct(id)` is replaced by `getProduct(id, callerRole)`, which applies `visibleStatus(null, callerRole)` to the loaded product and throws the same `ProductNotFoundException` for a hidden product as for a missing one. The controller takes the caller from `@AuthenticationPrincipal AuthenticatedUser`, exactly as `browseProducts` does, and returns `ProductResponse`. `ProductDetailsResponse` is deleted; `ProductResponse`'s comment now says it is the full view for every caller.
- **OpenAPI.** EP-CAT-05 carries `@OptionalAuthentication`, so `public.json` documents `security: [{bearerAuth: []}, {}]` on it; the descriptions of EP-CAT-04, its `status` parameter and EP-CAT-05 name both staff roles. The `ProductDetailsResponse` schema is gone from `public.json`, and EP-CAT-05 references `ProductResponse`.
- **Verified:**
  - `ProductServiceTest`: `visibleStatus` for both staff roles (no filter, `ACTIVE`, `NOT_ACTIVE`) and for anonymous, `CUSTOMER`, `ADMIN` (always `ACTIVE`); `getProduct` for an active product and every caller, an inactive product for staff, a missing product, and an inactive product for non-staff (same message as missing).
  - `ProductControllerTest`: EP-CAT-05 JSON with all seven fields including `status`, the worker's role reaching the service, `404` body.
  - `CatalogReadAndStatusIntegrationTest` (real security chain, PostgreSQL): EP-CAT-05 for an active product and all five callers; an inactive product `200` for `CATALOG_MANAGER` and `INVENTORY_WORKER` and a `404` identical to a missing product for anonymous, `CUSTOMER`, `ADMIN`; EP-CAT-04 visibility and the `status` filter for the same five callers.
  - `RouteAuthorizationTest` (unchanged rules, renamed service method), `OpenApiDocumentTest` after regenerating `public.json`.

## Deviations from spec / task
- None in behaviour. One detail decided here: `visibleStatus` stayed the single place of the rule, and EP-CAT-05 calls it with no requested status rather than getting its own role check. Alternative: a separate `canSee(product, role)` predicate; rejected because two predicates for one rule can drift apart.
