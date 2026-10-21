---
status: DONE
service: catalog-service
---
# EP-CAT-04/10: Uniform Paging Envelope (`size`)

## Context
Catalog's list endpoints answer `{ content, page, totalElements }`, while Order (`GET /orders`) and Inventory (`GET /inventory/stock`) answer `{ content, page, size, totalElements }`. Clients have to handle both shapes. The team agreed (2026-10-21) on one shared envelope with `size`, the effective page size. It is done before the OpenAPI pilot ([task 26](26-openapi-document.md)) so the first committed document already shows the uniform envelope. Requested by the frontend as backlog item BL-05.
*References:* [Catalog Spec — EP-CAT-04, EP-CAT-10, Revisions 2026-10-21](../../design/services-specs/catalog-service-spec.md), [API Contracts — Shared Conventions](../../design/services-requirements/api-contracts.md#shared-conventions)

## Acceptance Criteria
- [x] `PageResponse` has `size` (the effective page size: `Page.getSize()`, i.e. the requested `size` or the default `20`), between `page` and `totalElements`; its comment names the full envelope.
- [x] `GET /catalog/products` and `GET /catalog/categories` return `{ content, page, size, totalElements }`.
- [x] Tests: a request without `size` returns `size: 20`, and a request with `size=5` returns `size: 5`, for both endpoints (controller tests and the read integration test). Existing assertions on the envelope are updated.
- [x] The spec and `api-contracts.md` already describe the field (updated 2026-10-21); check that they match.

## Technical Notes / Constraints
- Additive change only: do not rename or remove `content`, `page` or `totalElements`, and do not add `totalPages`.
- `size` is the effective page size, not the number of items in `content` (the last page can be shorter).
- No other service changes; Order and Inventory already answer this shape.

## Decisions (2026-10-21)
- Implemented in one run together with the other springdoc tasks (catalog 26, auth 10, customer 11, inventory 21, order 18, delivery 18) on the branch `springdoc`, with separate commits per service and one PR.
- The run does not edit `docs/design/`; its final review lists where the spec needs an update, and the team decides on the sync. The branch is pushed only after that sync is approved.

## Implementation Notes (2026-10-21)
- `PageResponse` gained `int size` between `page` and `totalElements`, filled from `Page.getSize()` in `PageResponse.from`. That is the size of the `PageRequest` built by `Paging.pageRequest` (the requested `size` or `DEFAULT_SIZE` 20), so it is the effective page size even on a short last page. The class comment names the full envelope.
- Both list endpoints use `PageResponse.from`, so `GET /catalog/products` and `GET /catalog/categories` now answer `{ content, page, size, totalElements }` with no further change.
- The spec (EP-CAT-04 step 5, EP-CAT-10 step 3) and `api-contracts.md` (Shared Conventions, Catalog rows) already describe exactly this shape; checked, no difference.
- **Verified:** `CatalogReadAndStatusIntegrationTest.lists_reportTheEffectivePageSize` (both endpoints: no `size` → `20` with one item in `content`, `size=5` → `5`), plus `size` assertions in the existing paging tests of the same class; `CategoryServiceTest` (default size in the response); `CategoryControllerTest` and `ProductControllerTest` (the envelope serializes `size`); `SecurityConfigTest` (constructor update). 89 tests, all green.

## Deviations from spec / task
- **Where the default `20` is tested.** The task asks for the "without `size` → 20" case in the controller tests too. The controller tests mock the service, so the value they see is whatever the mock returns; asserting `20` there would only test the mock. The default is therefore checked where it is computed, in `CategoryServiceTest` and in the integration test for both endpoints, and the controller tests check only that `size` is serialized. Alternative considered: a controller test with a real `Paging` call through a spy — rejected as more setup for no extra coverage.
