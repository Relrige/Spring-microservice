# Proposal

## Why

The customer side of the demo is complete, but a catalog manager who logs in finds only a placeholder at `/manage`, so the Catalog Service's write side (EP-CAT-01…03, 06…08) and its staff visibility rule are never shown. The backend for the one missing piece, loading an inactive product with its description (BL-01), is now merged (Catalog task 27, gateway task 12), so the manager console can be built properly.

## What Changes

- **BL-01 verified first.** Against the Compose stack:
  - a manager's token gets `200` with the full view for an inactive product on `GET /catalog/products/{id}`;
  - no token gets `404`;
  - an invalid token gets `401`.

  BL-01 then moves to `Done`. If it fails, the work that depends on it stops (`AGENTS.md` §4).
- **Inventory types brought up to date.** Inventory tasks 22–24 added `title`, `productStatus`, `search` and `sortBy=title` to `inventory-service/openapi/public.json`, so `npm run check:api` fails on the current branch. The types are regenerated (additive; iteration 5 uses the new fields).
- **Products list** (`/manage/products`):
  - a staff table of all products, active and inactive, read with the manager's token;
  - a toolbar with search, category, status and sort; 20 per page; the list state kept in the URL;
  - per row: status and stock chips, "Edit", and "Activate" or "Deactivate".
- **New product** (`/manage/products/new`):
  - title, description, category and price, with Catalog's field rules;
  - the product is created inactive, and the manager lands on its edit page.
- **Edit product** (`/manage/products/:productId`):
  - the full product loaded with the manager's token, inactive ones included (BL-01);
  - save with `PUT`;
  - `409` (an overlapping save) offers to reload the product;
  - `422` (the category is gone) is shown at the category field.
- **Activate and deactivate** (list and edit page):
  - activation reports Inventory's readiness reasons in words (`packaging_data_missing`, `stock_item_not_found`, unknown codes) and `503` with its `Retry-After`;
  - deactivation asks for confirmation, because the product leaves the storefront.
- **Categories** (`/manage/categories`):
  - a paged, searchable table;
  - create and rename, with `409` "name in use" at the name field;
  - delete with confirmation, where `409` explains that products still belong to the category.
- **Navigation:**
  - `/manage` leads to `/manage/products`;
  - the manager header shows "Products" and "Categories" instead of "Catalog console".
- **Caches:**
  - the manager's catalog reads live under their own query keys, so staff results never appear in the storefront;
  - every manager write refreshes both the manager's and the storefront's catalog queries.
- **Mock backend:**
  - handlers for the six manager endpoints, with gateway `401`/`403`, Catalog's field rules and its problem types (`conflict`, `unprocessable-entity`, `activation-rejected`, `service-unavailable`);
  - EP-CAT-05 follows the staff visibility rule and answers `401` for a bad token;
  - activation readiness comes from the mock product's packaging; products created in the mock have none.
- **Browser journeys** (mock and live):
  - a manager creates a category and a product, sees its activation rejected for missing packaging, edits it, and deletes the empty category;
  - a manager deactivates an active product, checks it has left the storefront, and activates it again.

Not in this change: product images (BL-06), product attributes (BL-12), the worker's stock and packaging screens (iteration 5), and any view of inventory quantities for managers.

## Capabilities

### New Capabilities
- `product-management`: the catalog manager's product list, the create and edit forms, and activation and deactivation, including how Catalog's and Inventory's answers are shown.
- `category-management`: the catalog manager's category list, and creating, renaming and deleting categories.

### Modified Capabilities
- `app-navigation`:
  - *URL areas per role*: `/manage` leads to `/manage/products`;
  - *Role-aware navigation*: the manager header shows "Products" and "Categories".

## Impact

- Code under `frontend/` only; no service, gateway or spec changes.
- Backlog: BL-01 moves to `Done` after the live check.
- Gateway routes the app now uses, all of them needing `CATALOG_MANAGER` unless noted:
  - `POST /catalog/products`, `PUT /catalog/products/{id}`, `PATCH /catalog/products/{id}/status`;
  - `POST /catalog/categories`, `PUT /catalog/categories/{id}`, `DELETE /catalog/categories/{id}`;
  - `GET /catalog/products` and `GET /catalog/products/{id}`, now also called with the manager's token (routes `Optional`).
- New code:
  - the missing calls `updateProduct`, `renameCategory` and `deleteCategory`, and an `auth` option on `getProduct`, in `src/api/catalog.ts`;
  - a `src/features/manage/` folder with the list, form, status and category pages;
  - manager query keys;
  - mock handlers and a nullable packaging on mock products;
  - new Playwright journeys.
- Regenerated `src/api/generated/inventory.ts`.
- Live runs create a few catalog rows that cannot be removed (Catalog has no hard delete). The live journey reuses one fixed draft product to keep that bounded.
