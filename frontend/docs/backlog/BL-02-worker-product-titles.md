---
id: BL-02
title: Inventory workers can see product titles for stock rows
status: Open
importance: Blocker
services: [catalog-service]
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

- [ ] `GET /catalog/products` with role `INVENTORY_WORKER` returns products of every status; `status` filter works for workers
- [ ] `GET /catalog/products?ids=a,b,c` returns only those products (still filtered by the caller's visibility); more than 100 ids or a malformed id → `400`
- [ ] Anonymous and `CUSTOMER` callers still see `ACTIVE` products only
- [ ] Updated: catalog spec EP-CAT-04, `api-contracts.md`

## Frontend impact

- Until done: the worker console (iteration 5) shows product ids, and titles only for active products (looked up through the public list one by one). Marked in code with `BL-02`.
- After: one extra request per stock page fetches all titles.

## Notes

- Same visibility idea as [BL-01](BL-01-manager-reads-inactive-products.md) for managers.
