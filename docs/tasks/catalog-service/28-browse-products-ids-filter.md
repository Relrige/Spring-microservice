---
status: TODO
service: catalog-service
---
# EP-CAT-04: `ids` Filter on the Product Listing

## Context
Clients sometimes need the catalog data of a known set of products, for example the products shown on one page of another service's list. Without a filter by ID they need one request per product. The internal batch-get (EP-CAT-09) does this, but it is not routed and has no visibility rule. EP-CAT-04 gets an optional `ids` filter that combines with everything else (D4).

Builds on [task 27](27-staff-product-visibility.md) (the visibility rule the filter is combined with).

*References:* [Catalog spec — EP-CAT-04, revision 2026-10-22 (D4)](../../design/services-specs/catalog-service-spec.md), [API Contracts — Catalog](../../design/services-requirements/api-contracts.md#3-catalog-service), frontend [BL-02](../../../frontend/docs/backlog/BL-02-worker-product-titles.md)

## Acceptance Criteria
- [ ] `ProductSearchQuery` gets an optional `ids` (comma-separated UUIDs; repeated `ids=a&ids=b` also binds, as Spring does by default). `ProductSpecifications` gets `idIn(Collection<UUID>)`, added to the other filters with AND.
- [ ] `GET /catalog/products?ids=a,b,c` returns only those products, still filtered by the caller's visibility and the other filters, with normal paging and sorting.
- [ ] `400` with `errors.ids` when:
  - `ids` is present but empty;
  - an ID is not a UUID;
  - there are more than 100 distinct IDs (the same limit as EP-CAT-09; reuse the constant).
- [ ] Duplicate IDs are counted once. Unknown IDs and IDs hidden by visibility are simply absent; `totalElements` counts only the products returned.
- [ ] OpenAPI: the parameter is documented ("comma-separated product IDs, at most 100"), and `catalog-service/openapi/public.json` is regenerated and committed.
- [ ] Tests:
  - `ProductSpecificationsTest` covers `idIn`;
  - the integration test covers: `ids` alone, `ids` combined with `search` and `status`, an inactive ID for an anonymous caller (absent) and for a staff caller (present), duplicates, and the three `400` cases (empty, malformed, 101 distinct).

## Technical Notes / Constraints
- **Settled (D4):** the filter is on EP-CAT-04, not a routed batch-get. EP-CAT-09 stays internal and unchanged in this task.
- `ids` doesn't change paging. A caller that wants all of up to 100 products in one page sends `size=100`; document this in the parameter description.
- A malformed UUID inside the list must give the same `400` shape as the other query-parameter errors (check how `GlobalExceptionHandler` reports conversion failures for EP-CAT-09 and reuse it).
