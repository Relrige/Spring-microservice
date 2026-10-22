# Tasks

Apply on a branch that contains `customer-checkout-orders`, Catalog tasks 27–29, gateway task 12 (BL-01) and Inventory tasks 22–24. The branch `catalog-manager-console` at `71b3b18` contains all of them.

## 1. Backlog gate, contracts and mock backend

- [ ] 1.1 With the Compose stack up and `npm run seed` done, check BL-01 through the gateway for the inactive fixture "Xiaomi 14T, 512 GB" (design D1). Expected answers to `GET /api/catalog/products/{id}`:
  - with the manager's token: `200` with `description` and `status: NOT_ACTIVE`;
  - without a token: `404`;
  - with a malformed token: `401`.

  Record the result in *Live verification* of `README.md`, and set BL-01 to `Done` in its file and in the index row. If any check fails, stop and report: BL-01 stays a `Blocker`, and group 3 and the journeys of group 5 wait for a person.
- [ ] 1.2 Run `npm run gen:api`, review the Inventory diff (additive: `title`, `productStatus`, `search`, `sortBy=title`) and commit the regenerated `src/api/generated/inventory.ts`; verify `npm run check` passes
- [ ] 1.3 In `src/api/catalog.ts` (design D2):
  - add the `auth` option to `getProduct`, with the default `'none'`;
  - add `updateProduct`, `renameCategory` and `deleteCategory`, typed by operationId and citing EP-CAT-02/07/08;
  - export the `ActivationRejected` alias.

  Verify with:
  - type tests for the new aliases and bodies;
  - an http test that `getProduct(id, { auth: 'session' })` sends the token while the default sends none;
  - a test that `deleteCategory` resolves on a `200` with an empty body.
- [ ] 1.4 Make `MockProduct.packaging` nullable, give EP-CAT-05 the staff visibility rule with a `401` for a bad token, and add the six manager handlers of design D10. Verify with handler tests through the API calls for every status code and problem `type`:
  - `401` and `403` per route;
  - EP-CAT-05 for anonymous, `CUSTOMER`, `ADMIN`, `CATALOG_MANAGER` and `INVENTORY_WORKER` callers;
  - `422` `activation-rejected` for a product created in the mock;
  - `409` for names that differ only in case.

  The existing storefront and cart tests must stay green.

## 2. Routes, navigation and shared pieces

- [ ] 2.1 Replace the `/manage` placeholder with the routes of design D4. Change the manager's header links to "Products" and "Categories", with nested paths highlighting their link. Verify with navigation tests for *Manager opens the manager area*, *Unknown manager page*, *Manager header* and *Admin cannot open the manager area*, and check that the existing navigation and session tests still pass. The session test that expects `/manage` after a re-login now expects `/manage/products`.
- [ ] 2.2 Add `src/features/manage/`:
  - `queries.ts` with `manageKeys` and the D3 invalidation helper;
  - `url-state.ts` for the product and category lists;
  - `messages.ts` with the activation-reason table and the status-error mapping;
  - `ConfirmDialog`.

  Verify with unit tests for parsing and serializing both URL states (invalid values fall back, defaults are left out), for the query each state builds, and for every activation reason including the unknown-reason fallback with and without `detail`.

## 3. Products

- [ ] 3.1 Build `ProductListPage` (design D5): the staff table with chips and row actions, the toolbar (search, category, status, sort), paging, URL state, skeleton loading, the failure state with "Try again", and the empty state. Verify with component tests for *Inactive products are listed* (the request carries the token), *Row actions follow the status*, *Unknown category in a row*, *Only inactive products*, *List state restored from the URL*, *Nothing matches* and *Catalog unavailable*.
- [ ] 3.2 Build `ProductForm` and `ProductCreatePage` (design D6): client rules, trimmed values, server field errors, the `422` category handling, and the redirect to the edit page with the one-time "Product created…" notice. Verify with tests for *Product created*, *Category removed meanwhile*, *Blank description*, *Price of zero*, and a `400` with `errors.title` shown at the title field.
- [ ] 3.3 Build `ProductEditPage` (design D6, D8): load with the token, the status panel, the save with "Changes saved", the `409` alert with "Reload product", and the form reset only on first data and on reload. Verify with tests for:
  - *Inactive product opens for editing*, *Unknown product*, *New price saved*, *Two saves overlap* and *Reload after a conflict*;
  - a background refetch (window focus) that leaves typed values in place.
- [ ] 3.4 Build `ProductStatusControl` (design D7) and use it on the list and the edit page, with the D3 invalidation after each write. Verify with tests for:
  - *Product activated*, *Packaging missing*, *No stock record yet*, *Unknown reason*, *Inventory down* (exactly one request sent), *Product deactivated* and *Deactivation cancelled*;
  - a `409` on the list that refetches it;
  - *Deactivated product leaves the storefront* and *Staff list does not leak into the storefront*.

## 4. Categories

- [ ] 4.1 Build `CategoriesPage` (design D9): the table, search, paging and URL state, the empty state and the failure state. Verify with tests for *Categories listed*, *Search by name* and *No categories match*.
- [ ] 4.2 Build `CategoryDialog` for create and rename, and the delete confirmation, with the `409`, `404` and `400` handling of design D9 and the D3 invalidation. Verify with tests for:
  - *Blank name*, *Category created*, *Name in use*, *Category renamed*, *Category deleted meanwhile*, *Empty category deleted*, *Category with products* and *Deletion cancelled*;
  - *Renamed category in the product list* and *New category offered in the product form*.

## 5. Journeys, live run and docs

- [ ] 5.1 Write `e2e/manage.spec.ts` with the three journeys of design D11, plus the 375 px check on the product list; verify `npm run check:e2e` passes, including the existing journeys
- [ ] 5.2 Run `npm run test:e2e:live` against the seeded Compose stack. Fix any mismatch between the mock and Catalog; a generated type that disagrees with the service spec is a backlog item (`AGENTS.md` §3). Record the run in *Live verification* of `README.md`, and describe the manager console where `README.md` lists what each demo account can do. Verify that the live run passes and that a second run reuses "E2E draft product" instead of creating another.
- [ ] 5.3 Check the product list, the new and edit pages, and the categories page in the preview pane, on desktop and at 375 px, in light and dark, against the design system README's staff rules and `WarehouseStock.dc.html`. Verify by screenshots that layout and colours match and that the page has no horizontal scroll.

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive`, so `product-management` and `category-management` become main specs and the `app-navigation` delta is merged.
- Propose iteration 5 (`inventory-worker-console`) once BL-02 is `Done`; it is verified the same way as BL-01 in task 1.1.
