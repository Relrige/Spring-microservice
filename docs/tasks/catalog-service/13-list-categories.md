---
status: DONE
service: catalog-service
---
# Extra Endpoint: List and Search Categories

## Context
The spec has no way to read categories, yet a manager needs category IDs to create or edit a product, and the storefront needs them for the `categoryId` filter of EP-CAT-04. This endpoint is an addition to the spec agreed with the team (search by name and pagination). It is not yet described in the design docs, so adding it to them is part of this task.
*References:* [Catalog Spec — EP-CAT-04](../../design/services-specs/catalog-service-spec.md), [API Contracts — Catalog](../../design/services-requirements/api-contracts.md), [API Gateway Spec — Route Table](../../design/services-specs/api-gateway-spec.md)

## Acceptance Criteria
- [x] `GET /catalog/categories` with optional query parameters `search` (case-insensitive substring of `name`), `page`, `size`.
- [x] Returns `200 OK { content: [ { categoryId, name } ], page, totalElements }` (same envelope as EP-CAT-04). No match → `200` with empty `content`.
- [x] Results have a stable order (by `name`).
- [x] `size` has a default and an upper limit; invalid `page`/`size` (negative, zero size, over the limit) → `400`.
- [x] Public: `GET /catalog/categories` is `permitAll()` in the security chain (it exposes only names, and customers need it).
- [x] Documentation updated: add this endpoint as `EP-CAT-10` to the Catalog spec, a row to [api-contracts.md](../../design/services-requirements/api-contracts.md), and a `Public` row (`GET /catalog/categories`) to the gateway route table.
- [x] Tests: repository/service tests for search and paging; controller tests for the envelope, validation, and anonymous access.

## Technical Notes / Constraints
- Escape `%` and `_` in the `search` text if using `LIKE`; otherwise a search for `50%` matches everything. Decide how and test it.
- The paging envelope and the `page`/`size` rules are reused by [task 16](16-browse-and-filter-products.md). Implement the envelope as a small generic DTO so both endpoints share it, and document whether `page` is zero-based.
- Do not return the full table: even a short category list should go through the same paging code path to keep behaviour uniform.
- **Search escaping (decision):** a Spring Data derived query, `findByNameContainingIgnoreCase`, which escapes `%` and `_` in the argument itself. Repository tests prove that `50%` and `USB_C` match literally.
- **Paging (decision):** generic `dto/PageResponse<T>` `{ content, page, totalElements }` plus `utils/Paging`. `page` is **zero-based**, `size` defaults to `20` with a maximum of `100`. Invalid values (negative, zero, over the limit, non-numeric) → `400` with field errors. Order: `name`, then `id` as a tiebreaker.
