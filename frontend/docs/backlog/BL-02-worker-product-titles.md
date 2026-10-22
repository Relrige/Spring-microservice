---
id: BL-02
title: Inventory workers can see product titles for stock rows
status: In progress
importance: Blocker
services: [catalog-service, inventory-service]
blocks: iteration 5 (inventory-worker-console)
created: 2026-10-20
---

# BL-02: Inventory workers can see product titles for stock rows

## Problem

The worker's stock table, restock form and packaging form identify products only by UUID. Workers need the product title, also for `NOT_ACTIVE` products: packaging and stock must be prepared **before** a product can be activated, so most products a worker sets up are inactive.

## Current behaviour

- `GET /inventory/stock` (EP-INV-01, [inventory spec](../../../docs/design/services-specs/inventory-service-spec.md#ep-inv-01-view-stock-balances)) returns `{ productId, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured }`, no title.
- `GET /catalog/products` (EP-CAT-04) shows `NOT_ACTIVE` products only to `CATALOG_MANAGER`; a worker gets active products only. It has no filter by a list of ids either, so titles for one page of stock rows would need many requests.
- `GET /catalog/products/batch-get` has titles for any status but is internal (not routed).
- The gateway route `catalog-browse-products` is already `OPTIONAL`, so Catalog sees the worker's role.

## Proposed change

Recommended (team to confirm):
1. EP-CAT-04: treat `INVENTORY_WORKER` like `CATALOG_MANAGER` for visibility (all statuses, optional `status` filter).
2. EP-CAT-04: new optional query parameter `ids` (comma-separated UUIDs, at most 100, the page size limit). The frontend asks for the titles of one stock page in one call.

Alternatives considered:
- Inventory stores and returns titles. `ProductCreated` carries only `productId`, so this means enriching the event and keeping titles in sync on rename: more coupling for a display need. Not recommended.
- Route `batch-get` publicly for workers. It is an internal contract (Order and Inventory depend on it), so exposing it mixes internal and external callers. Not recommended.

## Requirements

- [x] `GET /catalog/products` with role `INVENTORY_WORKER` returns products of every status; `status` filter works for workers
- [x] `GET /catalog/products?ids=a,b,c` returns only those products (still filtered by the caller's visibility); more than 100 ids or a malformed id → `400`
- [x] Anonymous and `CUSTOMER` callers still see `ACTIVE` products only
- [x] Updated: catalog spec EP-CAT-04, `api-contracts.md`

## Frontend impact

- Until done: the worker console (iteration 5) shows product ids, and titles only for active products (looked up through the public list one by one). Marked in code with `BL-02`.
- After: one extra request per stock page fetches all titles.

## Backend decision (2026-10-22)

Both recommended changes are accepted, and the backend goes one step further, because joining titles in the browser only works within one page (no search or sort by title across the whole stock list):
1. `INVENTORY_WORKER` is staff on `GET /catalog/products` **and** `GET /catalog/products/{id}`: all statuses, `status` filter (Catalog D1).
2. `GET /catalog/products` gets the `ids` filter as proposed (Catalog D4).
3. **New:** Inventory keeps a copy of each product's title and status, updated from Catalog's product events, so `GET /inventory/stock` returns `title` and `productStatus` per row (both `null` until copied) and supports `search` (title substring), `productStatus=ACTIVE|NOT_ACTIVE` and `sortBy=title` (Inventory D8–D10). The worker console then needs no extra request for titles; the copy is eventually consistent (a rename shows up after the event arrives).

Recorded in the [Catalog spec](../../../docs/design/services-specs/catalog-service-spec.md) and [Inventory spec](../../../docs/design/services-specs/inventory-service-spec.md) revisions of 2026-10-22. The earlier alternative "Inventory stores titles" was taken after all, with full-state events and a per-product version instead of event enrichment on rename only.

Backend tasks: Catalog [27](../../../docs/tasks/catalog-service/27-staff-product-visibility.md), [28](../../../docs/tasks/catalog-service/28-browse-products-ids-filter.md), [29](../../../docs/tasks/catalog-service/29-product-version-and-full-state-events.md); Inventory [22](../../../docs/tasks/inventory-service/22-product-data-copy-from-events.md), [23](../../../docs/tasks/inventory-service/23-product-data-backfill.md), [24](../../../docs/tasks/inventory-service/24-stock-list-title-search-and-sort.md).

## Backend progress (2026-10-22)

- **Done in Catalog** (tasks 27–29, [PR #22](https://github.com/Relrige/Spring-microservice/pull/22), not merged yet):
  - `GET /catalog/products` shows `INVENTORY_WORKER` products of every status, and the `status` filter works for workers;
  - `GET /catalog/products?ids=a,b,c` (at most 100 distinct IDs; repeated `ids=` also works) returns only those products, still filtered by visibility; empty, malformed or more than 100 IDs → `400` with `errors.ids`;
  - `GET /catalog/products/{id}` gives workers inactive products too (but see BL-01: the gateway route is not `OPTIONAL` yet);
  - Catalog now publishes full-state `ProductCreated` and `ProductUpdated` events, which Inventory will use for its copy of titles.
- **Usable through the gateway as soon as the PR is merged:** the list with `ids` and the worker's visibility (route `catalog-browse-products` is already `OPTIONAL`). That replaces the "one request per title" workaround (`BL-02` comments in the code).
- **Done in Inventory** (tasks 22–24, [PR #25](https://github.com/Relrige/Spring-microservice/pull/25), stacked on PR #22, not merged yet):
  - each row of `GET /inventory/stock` adds `title` and `productStatus` (`ACTIVE`/`NOT_ACTIVE`). Both are optional in `inventory-service/openapi/public.json` and are missing or `null` until copied, so `npm run gen:api` adds them as optional fields;
  - new query parameters `search` (case-insensitive part of the title; `%` and `_` match literally; blank means no filter) and `productStatus`; both combine with `packagingConfigured` and paging;
  - `sortBy=title` (and `title,desc`) puts rows without a title last;
  - an invalid `productStatus` (and now also an invalid `packagingConfigured`) → `400` with `errors.<parameter>`;
  - the copy comes from Catalog's product events and a backfill job, so a rename shows up a moment later (eventually consistent). Rows show no title until Catalog's PR #22 is deployed too.
- **Next:** once both PRs are merged, the worker console can take titles from the stock list itself and drop the per-title lookups (`BL-02` comments in the code). The item stays `In progress` until the frontend has verified this.

## Notes

- Same visibility idea as [BL-01](BL-01-manager-reads-inactive-products.md) for managers.
