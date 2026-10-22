---
status: DONE
service: inventory-service
---
# EP-INV-01: Product Title and Status in the Stock List, Search and Sort by Title

## Context
With the copied product data in `stock_items` (tasks [22](22-product-data-copy-from-events.md) and [23](23-product-data-backfill.md)), the worker's stock list can show each product's title and status. It can also search, filter and sort by them in the database, with normal paging and combined with the existing filters (D10). This removes the need for the frontend to look up titles per page (frontend backlog BL-02).

Builds on task 22 (columns and entity mapping). It can be done before task 23; rows without a copy simply show `null`.

*References:* [Inventory spec — EP-INV-01, List responses, Copy of product data, revision 2026-10-22 (D10)](../../design/services-specs/inventory-service-spec.md), [API Contracts — Inventory](../../design/services-requirements/api-contracts.md#4-inventory-service), frontend [BL-02](../../../frontend/docs/backlog/BL-02-worker-product-titles.md)

## Acceptance Criteria
- [x] Each row of the `GET /inventory/stock` response adds `title` and `productStatus` (both nullable): `{ productId, title, productStatus, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured }`.
- [x] New optional query parameters, combined with `packagingConfigured` using AND:
  - `search`: case-insensitive substring of `title`, with `%` and `_` matched literally; a blank value means no filter;
  - `productStatus=ACTIVE|NOT_ACTIVE`.
- [x] `sortBy` accepts `title` (`asc`/`desc`). Rows without a title come last in both directions, and `productId` stays the final tiebreaker.
- [x] `400` with `errors.<parameter>` for an invalid `productStatus` or an unknown sort field, as today.
- [x] OpenAPI: the new fields and parameters are documented (nullable fields marked as such, `title` listed among the sort fields). `inventory-service/openapi/public.json` is regenerated and committed.
- [x] Tests:
  - `StockItemSpecifications` (or a repository test) covers `search`, including `%` and `_` literally and case-insensitivity, and covers `productStatus`;
  - the controller test checks the JSON of a row with and without a copy;
  - the integration test covers:
    - `search` combined with `packagingConfigured=false` and paging;
    - `sortBy=title` and `sortBy=title,desc`, with a `null` title last in both;
    - `productStatus=NOT_ACTIVE`;
    - an invalid `productStatus` → `400`.

## Technical Notes / Constraints
- **Settled (D10):** the parameter is named `productStatus` and the response field `productStatus`, so they can't be confused with stock or reservation statuses.
- **Settled (D8):** no Catalog call on this read path. Title and status come only from the copy, which is eventually consistent.
- Sorting with nulls last: Spring Data's `Sort.Order.nullsLast()` with Hibernate, or an explicit `CASE WHEN title IS NULL` expression in the specification. Verify with the integration test on PostgreSQL, where the default puts `NULL` first for `DESC`.
- Reuse Catalog's approach to escaping `LIKE` (`EscapeCharacter`), but write it in Inventory: no shared library between services.
- No index on `lower(title)` is needed at this project's size; mention it in a comment, as Catalog does.

## Implementation Notes (2026-10-22)
- **Response.** `StockBalanceResponse` adds `title` and `productStatus` (Inventory's `ProductStatus` enum) after `productId`, taken from the entity's read-only copy. Following `openapi-conventions.md` D5, both are unmarked, so the OpenAPI schema shows them as optional, each with a description saying it is missing until copied. `productVersion` is never exposed.
- **Filters.** `StockService.listStock(packagingConfigured, search, productStatus, pageable)` adds two specifications from `StockItemSpecifications`:
  - `titleContains(text)`: `lower(title) LIKE %…%` with Spring Data's `EscapeCharacter`, the same approach as Catalog's, written in Inventory. The search is trimmed, and a blank one adds no filter.
  - `hasProductStatus(status)`.
  - A `NULL` copy matches neither filter.
- **Sort.** `PageableParser` takes an optional set of nulls-last properties. For those it builds `Sort.Order…nullsLast()`, which Spring Data JPA and Hibernate translate to `NULLS LAST` on PostgreSQL in both directions. `StockController` adds `title` to the sortable fields and marks it nulls-last; `productId` remains the final tiebreaker.
- **`400` for unconvertible parameters.** `GlobalExceptionHandler.handleTypeMismatch` now names the parameter in `errors`, for example `productStatus must be one of [ACTIVE, NOT_ACTIVE]` or `packagingConfigured must be true or false`. The problem type stays `validation`. An unknown `sortBy` field still goes through `InvalidQueryParameterException`.
- **OpenAPI.** `public.json` is regenerated: the `search` and `productStatus` parameters, `title` in the `sortBy` description, and the two new response properties. `internal.json` is unchanged.
- **Verified:**
  - `StockItemRepositoryTest`: `titleContains` is case-insensitive and matches `%` and `_` literally (`50%` doesn't match "Charger 50 off", `e_u` doesn't match "Cable-USB"); `hasProductStatus` for both values, and rows without a copy match neither.
  - `PageableParserTest`: nulls-last for `title` in both directions, and other fields unchanged.
  - `StockListControllerTest`:
    - JSON of a row with a copy (`title`, `productStatus`, no `productVersion`) and of a row without one (`null`);
    - `search` and `productStatus` reach the service;
    - `sortBy=title` → nulls-last order plus the tiebreaker;
    - invalid `productStatus` (`archived`, `active`, `UNKNOWN`) → `400` with `errors.productStatus`;
    - invalid boolean → `errors.packagingConfigured`;
    - `title,up` and `productStatus` as sort fields → `400`.
  - `StockListIntegrationTest` (Testcontainers PostgreSQL, real MVC and security):
    - `search` (trimmed, any case) combined with `packagingConfigured=false` over two pages of two;
    - a blank `search` doesn't filter;
    - `50%` matches literally, and `_` matches nothing;
    - `sortBy=title` and `title,desc` with both rows without a title last, in `productId` order;
    - `productStatus=NOT_ACTIVE`;
    - the JSON of rows with and without a copy;
    - `productStatus=ARCHIVED` → `400`.
  - `StockServiceTest` adapted; `OpenApiDocumentTest` green after the regeneration.

## Deviations from spec / task
- **The type-mismatch handler is general.** The task asks for `errors.productStatus`; overriding `handleTypeMismatch` gives every unconvertible query parameter its `errors.<parameter>`, including `packagingConfigured`, which before answered `400` without `errors`. The other option, binding `productStatus` as a string and parsing it by hand, would have documented it as a free string in OpenAPI rather than an enum.
- **An empty `productStatus=` means no filter**, not `400`: Spring's enum conversion turns an empty value into `null`. This matches how a blank `search` behaves.
- **`search` is trimmed** before matching, as Catalog does for its `search`, so surrounding spaces typed into a search box don't hide every row.
