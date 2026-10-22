---
id: BL-05
title: One paging envelope for all list endpoints
status: Done
importance: Nice to have
services: [catalog-service]
blocks: —
created: 2026-10-20
---

# BL-05: One paging envelope for all list endpoints

## Problem

Paged lists answer in two slightly different shapes, so every client has to handle both. It is a small inconsistency, but it is exactly what a shared contract (BL-04) should not have.

## Current behaviour

*As of 2026-10-20, before the backend work; see* Delivered *below.*


- Catalog `GET /catalog/products` and `GET /catalog/categories`: `{ content, page, totalElements }`. No `size` (`PageResponse` in catalog-service, `dto/PageResponse.java`).
- Order `GET /orders` and Inventory `GET /inventory/stock`: `{ content, page, size, totalElements }`.
- `page` is zero-based everywhere.

## Proposed change

Add `size` (the effective page size) to Catalog's `PageResponse`, so all services answer `{ content, page, size, totalElements }`. Adding a field is backward compatible. Optionally add `totalPages` everywhere at the same time.

## Delivered (2026-10-22)

Implemented as proposed in `../../../docs/tasks/catalog-service/25-uniform-paging-envelope.md`, on PR [Relrige/Spring-microservice#20](https://github.com/Relrige/Spring-microservice/pull/20) (merged 2026-10-22). Differences from the request:

- **No `totalPages`**, here or in any other service (the optional part was not taken). Compute it as `Math.ceil(totalElements / size)` where a pager needs it.
- `size` is the **effective** page size: the requested `size`, or the default `20`. It is not the number of items in `content`, so a short last page still answers the requested size.
- In the OpenAPI documents (BL-04), all four fields are required, and the envelope schema is named per element type, for example `PageResponseProductSummaryResponse` or `PageResponseCategoryResponse`.

## Requirements

- [x] Catalog list responses include `size`
- [x] The envelope is described once (`api-contracts.md`, *Shared Conventions*) and all paged endpoints reference it
- [x] Updated: catalog spec EP-CAT-04 and EP-CAT-10
- [x] The frontend reads `size` from Catalog lists (frontend side; marks the item `Done`)

## Frontend impact

Done in the OpenSpec change `api-contract-types` (2026-10-22): every list returns the one `Page<T>` of `src/api/contract.ts` (`{ content, page, size, totalElements }`, checked against the generated envelopes), the mock Catalog lists answer `size`, and the storefront pager computes the page count from the answered `size`.
