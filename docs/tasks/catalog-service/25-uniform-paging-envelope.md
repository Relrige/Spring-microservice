---
status: TODO
service: catalog-service
---
# EP-CAT-04/10: Uniform Paging Envelope (`size`)

## Context
Catalog's list endpoints answer `{ content, page, totalElements }`, while Order (`GET /orders`) and Inventory (`GET /inventory/stock`) answer `{ content, page, size, totalElements }`. Clients have to handle both shapes. The team agreed (2026-10-21) on one shared envelope with `size`, the effective page size. It is done before the OpenAPI pilot ([task 26](26-openapi-document.md)) so the first committed document already shows the uniform envelope. Requested by the frontend as backlog item BL-05.
*References:* [Catalog Spec — EP-CAT-04, EP-CAT-10, Revisions 2026-10-21](../../design/services-specs/catalog-service-spec.md), [API Contracts — Shared Conventions](../../design/services-requirements/api-contracts.md#shared-conventions)

## Acceptance Criteria
- [ ] `PageResponse` has `size` (the effective page size: `Page.getSize()`, i.e. the requested `size` or the default `20`), between `page` and `totalElements`; its comment names the full envelope.
- [ ] `GET /catalog/products` and `GET /catalog/categories` return `{ content, page, size, totalElements }`.
- [ ] Tests: a request without `size` returns `size: 20`, and a request with `size=5` returns `size: 5`, for both endpoints (controller tests and the read integration test). Existing assertions on the envelope are updated.
- [ ] The spec and `api-contracts.md` already describe the field (updated 2026-10-21); check that they match.

## Technical Notes / Constraints
- Additive change only: do not rename or remove `content`, `page` or `totalElements`, and do not add `totalPages`.
- `size` is the effective page size, not the number of items in `content` (the last page can be shorter).
- No other service changes; Order and Inventory already answer this shape.
