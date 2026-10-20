---
id: BL-05
title: One paging envelope for all list endpoints
status: Open
importance: Nice to have
services: [catalog-service]
blocks: —
created: 2026-10-20
---

# BL-05: One paging envelope for all list endpoints

## Problem

Paged lists answer in two slightly different shapes, so every client has to handle both. It is a small inconsistency, but it is exactly what a shared contract (BL-04) should not have.

## Current behaviour

- Catalog `GET /catalog/products` and `GET /catalog/categories`: `{ content, page, totalElements }`. No `size` (`PageResponse` in catalog-service, `dto/PageResponse.java`).
- Order `GET /orders` and Inventory `GET /inventory/stock`: `{ content, page, size, totalElements }`.
- `page` is zero-based everywhere.

## Proposed change

Add `size` (the effective page size) to Catalog's `PageResponse`, so all services answer `{ content, page, size, totalElements }`. Adding a field is backward compatible. Optionally add `totalPages` everywhere at the same time.

## Requirements

- [ ] Catalog list responses include `size`
- [ ] The envelope is described once (e.g. in `api-contracts.md`) and all paged endpoints reference it
- [ ] Updated: catalog spec EP-CAT-04 and EP-CAT-10

## Frontend impact

- Until done: the API layer fills `size` from the requested page size for Catalog lists.
- After: that adapter is removed.
