---
status: DONE
service: catalog-service
---
# EP-CAT-04: `ids` Filter on the Product Listing

## Context
Clients sometimes need the catalog data of a known set of products, for example the products shown on one page of another service's list. Without a filter by ID they need one request per product. The internal batch-get (EP-CAT-09) does this, but it is not routed and has no visibility rule. EP-CAT-04 gets an optional `ids` filter that combines with everything else (D4).

Builds on [task 27](27-staff-product-visibility.md) (the visibility rule the filter is combined with).

*References:* [Catalog spec — EP-CAT-04, revision 2026-10-22 (D4)](../../design/services-specs/catalog-service-spec.md), [API Contracts — Catalog](../../design/services-requirements/api-contracts.md#3-catalog-service), frontend [BL-02](../../../frontend/docs/backlog/BL-02-worker-product-titles.md)

## Acceptance Criteria
- [x] `ProductSearchQuery` gets an optional `ids` (comma-separated UUIDs; repeated `ids=a&ids=b` also binds, as Spring does by default). `ProductSpecifications` gets `idIn(Collection<UUID>)`, added to the other filters with AND.
- [x] `GET /catalog/products?ids=a,b,c` returns only those products, still filtered by the caller's visibility and the other filters, with normal paging and sorting.
- [x] `400` with `errors.ids` when:
  - `ids` is present but empty;
  - an ID is not a UUID;
  - there are more than 100 distinct IDs (the same limit as EP-CAT-09; reuse the constant).
- [x] Duplicate IDs are counted once. Unknown IDs and IDs hidden by visibility are simply absent; `totalElements` counts only the products returned.
- [x] OpenAPI: the parameter is documented ("comma-separated product IDs, at most 100"), and `catalog-service/openapi/public.json` is regenerated and committed.
- [x] Tests:
  - `ProductSpecificationsTest` covers `idIn`;
  - the integration test covers: `ids` alone, `ids` combined with `search` and `status`, an inactive ID for an anonymous caller (absent) and for a staff caller (present), duplicates, and the three `400` cases (empty, malformed, 101 distinct).

## Technical Notes / Constraints
- **Settled (D4):** the filter is on EP-CAT-04, not a routed batch-get. EP-CAT-09 stays internal and unchanged in this task.
- `ids` doesn't change paging. A caller that wants all of up to 100 products in one page sends `size=100`; document this in the parameter description.
- A malformed UUID inside the list must give the same `400` shape as the other query-parameter errors (check how `GlobalExceptionHandler` reports conversion failures for EP-CAT-09 and reuse it).

## Implementation Notes (2026-10-22)
- **Query and filter.** `ProductSearchQuery.ids` is a `List<UUID>`; Spring's data binder accepts both `ids=a,b` and repeated `ids=a&ids=b`. `ProductSpecifications.idIn(Collection<UUID>)` is a plain `IN (...)`, added to the other filters with AND, so visibility, `search`, `categoryId`, prices, paging and sorting apply as before.
- **Validation.** The checks of EP-CAT-09 moved into one private helper `ProductService.distinctIds`, used by both endpoints: present but empty, an empty element (`a,,b` binds `null`), or more than `BATCH_GET_MAX_IDS` (100) distinct IDs → `400` with `errors.ids`. A malformed UUID fails binding, which `GlobalExceptionHandler.handleMethodArgumentNotValid` already reports as `errors.ids: ["Invalid value"]`. Duplicates count once (a `LinkedHashSet`).
- **OpenAPI.** `ids` is documented as an array of UUIDs with the description from the task (at most 100, `size=100` for one page); `public.json` regenerated.
- **Verified:**
  - `ProductSpecificationsTest`: `idIn` alone, with an unknown ID, and combined with `hasStatus`.
  - `CatalogReadAndStatusIntegrationTest`: `ids` alone for an anonymous caller (inactive and unknown IDs absent, `totalElements` = 2); repeated parameters with duplicates (one row); `ids` with `search`; `ids` with `sortBy=basePrice,desc` and `size=1` (paging unchanged); an `INVENTORY_WORKER` sees the inactive ID; a manager with `status=NOT_ACTIVE` sees only it; `400` with `errors.ids` for empty, malformed, an empty element and 101 distinct IDs; 101 copies of one ID → `200`.
  - `ProductServiceTest` (EP-CAT-09 checks after the refactoring), `OpenApiDocumentTest`.

## Deviations from spec / task
- **Empty element in the list** (`ids=a,,b`) also answers `400`, as it already did for EP-CAT-09; the task lists only empty, malformed and too many. Treating it as the same case as "malformed" keeps both endpoints identical.
- **OpenAPI style.** Springdoc documents `ids` as an array with the default `form` style and `explode: true` (repeated parameters), although the description says comma-separated. Both forms bind, so a generated client works either way; overriding the style (`explode = false`) was not worth a custom annotation.

*Spec sync (2026-10-22):* now part of the Catalog spec (EP-CAT-04 errors: the empty element; revision 2026-10-22, implementation note).
