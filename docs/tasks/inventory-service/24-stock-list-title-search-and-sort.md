---
status: TODO
service: inventory-service
---
# EP-INV-01: Product Title and Status in the Stock List, Search and Sort by Title

## Context
With the copied product data in `stock_items` (tasks [22](22-product-data-copy-from-events.md) and [23](23-product-data-backfill.md)), the worker's stock list can show each product's title and status. It can also search, filter and sort by them in the database, with normal paging and combined with the existing filters (D10). This removes the need for the frontend to look up titles per page (frontend backlog BL-02).

Builds on task 22 (columns and entity mapping). It can be done before task 23; rows without a copy simply show `null`.

*References:* [Inventory spec — EP-INV-01, List responses, Copy of product data, revision 2026-10-22 (D10)](../../design/services-specs/inventory-service-spec.md), [API Contracts — Inventory](../../design/services-requirements/api-contracts.md#4-inventory-service), frontend [BL-02](../../../frontend/docs/backlog/BL-02-worker-product-titles.md)

## Acceptance Criteria
- [ ] Each row of the `GET /inventory/stock` response adds `title` and `productStatus` (both nullable): `{ productId, title, productStatus, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured }`.
- [ ] New optional query parameters, combined with `packagingConfigured` using AND:
  - `search`: case-insensitive substring of `title`, with `%` and `_` matched literally; a blank value means no filter;
  - `productStatus=ACTIVE|NOT_ACTIVE`.
- [ ] `sortBy` accepts `title` (`asc`/`desc`). Rows without a title come last in both directions, and `productId` stays the final tiebreaker.
- [ ] `400` with `errors.<parameter>` for an invalid `productStatus` or an unknown sort field, as today.
- [ ] OpenAPI: the new fields and parameters are documented (nullable fields marked as such, `title` listed among the sort fields). `inventory-service/openapi/public.json` is regenerated and committed.
- [ ] Tests:
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
