---
id: BL-01
title: Catalog managers can read any product by id
status: Open
importance: Blocker
services: [catalog-service, api-gateway]
blocks: iteration 4 (catalog-manager-console)
created: 2026-10-20
---

# BL-01: Catalog managers can read any product by id

## Problem

The catalog manager's edit form needs the full product (title, **description**, category, price, status), including for `NOT_ACTIVE` products, which is every product right after creation. No routed endpoint returns that today, so the form cannot load the description of a product the manager is preparing.

## Current behaviour

- `GET /catalog/products/{id}` (EP-CAT-05, [catalog spec](../../../docs/design/services-specs/catalog-service-spec.md#ep-cat-05-get-product-details)) answers `404` for `NOT_ACTIVE` products, for every caller. The spec says "managers use EP-CAT-04 to view inactive products", but that is imprecise: EP-CAT-04 rows are `{ productId, title, basePrice, categoryId, status, stockStatus }`, with **no `description`**.
- Gateway route `catalog-get-product` is `PUBLIC`, so Catalog never sees the caller's role on this path.
- Code: `ProductController.getProduct` calls `productService.getActiveProduct(id)` and returns `ProductDetailsResponse` (no `status`). A `ProductResponse` ("Full (manager) view of a product", all fields including `status`) already exists, but only `PUT /catalog/products/{id}` returns it.

## Proposed change

**Decision (2026-10-20): option A.** Role-based visibility on EP-CAT-05, the same rule EP-CAT-04 already uses:
- Caller with role `CATALOG_MANAGER` → any product, active or not.
- Anyone else (anonymous, `CUSTOMER`, other roles) → active products only; `NOT_ACTIVE` stays `404`.
- One response shape for everyone: the full view (`productId, title, description, categoryId, basePrice, status, stockStatus`). Customers only ever see `status: ACTIVE`.
- Gateway route `catalog-get-product`: `PUBLIC` → `OPTIONAL`, so the role reaches Catalog.

Rejected: a separate back-office path such as `GET /catalog/manage/products/{id}`. It would put the single-product read under a different rule from the list (EP-CAT-04), which is already role-based on the shared path.

## Requirements

- [ ] `GET /catalog/products/{id}` with role `CATALOG_MANAGER` returns `200` with the full view for an `ACTIVE` and for a `NOT_ACTIVE` product
- [ ] Without a token, or with any other role, a `NOT_ACTIVE` product still answers `404`, and an active one `200`
- [ ] Response includes `status` for every caller; `description` is present
- [ ] Gateway route `catalog-get-product` is `OPTIONAL`; an invalid token on it now answers `401` (behaviour of `OPTIONAL` mode)
- [ ] Updated: catalog spec EP-CAT-05 (caller, steps, response), `api-contracts.md`, gateway spec route table and the gateway route test

## Frontend impact

- Until done: the manager console (iteration 4) cannot be delivered properly; editing an inactive product would start with an empty description and overwrite it on save. The storefront is unaffected because it sends no token on catalog reads.
- After: the edit form loads the product by id with the manager's token.

## Backend decision (2026-10-22)

Accepted as proposed (option A), and generalized together with BL-02: **staff** (`CATALOG_MANAGER` and `INVENTORY_WORKER`) see `NOT_ACTIVE` products on both `GET /catalog/products` and `GET /catalog/products/{id}`; `ADMIN` is not staff. EP-CAT-05 answers the full view `{ productId, title, description, categoryId, basePrice, status, stockStatus }` to everyone (the generated type `ProductDetailsResponse` disappears in favour of `ProductResponse`), and the route becomes `Optional`. Recorded as D1–D3 in the [Catalog spec](../../../docs/design/services-specs/catalog-service-spec.md) revision 2026-10-22.

Separate change worth knowing for the edit form: `PUT /catalog/products/{id}` and `PATCH …/status` can now answer `409` when two saves of the same product overlap (D5).

Backend tasks: Catalog [27](../../../docs/tasks/catalog-service/27-staff-product-visibility.md), gateway [12](../../../docs/tasks/api-getaway/12-optional-product-details-route.md). Status stays `Open` until they are implemented.

## Notes

- Related: [BL-02](BL-02-worker-product-titles.md) applies the same "staff see all statuses" idea to inventory workers on the list endpoint.
