# Design

## Context

- Iterations 1, 1a, 2 and 3 are archived. Their decisions hold and are referred to as *foundation D1–D16*, *cart D1–D11* and *checkout D1–D12*:
  - one API layer over the generated types (`AGENTS.md` §3);
  - TanStack Query, with mutations never retried, and the cache cleared on login and logout (`SessionProvider`);
  - `RequireRole` areas and the `AREAS` table in `src/session/roles.ts`;
  - the MSW mock shared by every test level;
  - demo fixtures shared by the mock and `npm run seed`.
- **Catalog contract:** `../docs/design/services-specs/catalog-service-spec.md` (revision 2026-10-22) and `catalog-service/openapi/public.json`, already generated into `src/api/generated/catalog.ts`.
  - operationIds: `browseProducts`, `getProduct`, `createProduct` (`201 { productId }`), `updateProduct` (`200 ProductResponse`), `changeStatus` (`200`, no body), `listCategories`, `createCategory` (`201`), `renameCategory` (`200`), `deleteCategory` (`200`, no body).
  - Problem kinds by `type`:
    - `validation` (`400` with `errors`) and `bad-request` (`400` without);
    - `not-found`;
    - `conflict` (`409`): name in use, category still has products, or overlapping product saves;
    - `unprocessable-entity` (`422`, category not found);
    - `activation-rejected` (`422` with `reason`, `ActivationRejectedProblem`);
    - `service-unavailable` (`503` with `Retry-After`).
  - Field limits from the schemas: title ≤ 200, description ≤ 5000, category name ≤ 100, `basePrice` > 0. Category names are unique ignoring case (`existsByNameIgnoreCase`).
- **Staff visibility:** `CATALOG_MANAGER` and `INVENTORY_WORKER` see `NOT_ACTIVE` products on EP-CAT-04 and EP-CAT-05, and both routes are `Optional` in the gateway. A bad token on them is `401`. The storefront keeps sending no token (`catalog-browsing`).
- **What exists in `src/api/catalog.ts`:** `listProducts` (with an `auth` option), `getProduct` (always `auth: 'none'`), `listCategories`, `createCategory`, `createProduct` and `setProductStatus` (used by `scripts/seed.ts`). Missing: `updateProduct`, `renameCategory` and `deleteCategory`.
- **Mock today:** only the three catalog reads are mocked. EP-CAT-05 answers `404` for every inactive product, whoever calls. Mock products always have packaging.
- **Drift:** `npm run check:api` fails on this branch, because Inventory tasks 22–24 added `title`, `productStatus`, `search` and `sortBy=title` to `inventory-service/openapi/public.json`. The change is additive, and no frontend code reads those types yet apart from `scripts/seed.ts`.
- **Design:** there is no manager mockup. The pages follow the staff rules of `docs/design/design-system/README.md` and the nearest staff mockup, `WarehouseStock.dc.html`:
  - full-width tables in `small` text, with `label` column headers on `surface-sunken`;
  - a filter toolbar above the table and row actions on the right;
  - the 1:1 placeholder image in table rows;
  - chips: "Active" `success`, "Inactive" `warning`.
- **Backlog:** BL-01 is `In progress`, and its backend is merged (Catalog task 27, gateway task 12 at `71b3b18`). The person running this change decided to propose it now and verify BL-01 as the first task. BL-06 and BL-12 stay out of scope.

## Goals / Non-Goals

**Goals:**
- A manager never overwrites a product with values they did not load: the edit form always starts from the server's full product (BL-01), and it is reset only when the manager asks for it.
- Staff results and customer results never share a cache entry.
- Every Catalog and Inventory refusal is explained in words the manager can act on.

**Non-Goals:**
- Showing stock quantities or packaging to managers: Inventory's reads are worker-only, and packaging is iteration 5.
- Bulk actions, CSV import, or reassigning products between categories in one step.
- Detecting stale edits across time (no `If-Match`): Catalog detects only overlapping transactions (*Product version*), and the frontend does not invent more.

## Decisions

### D1. Verify BL-01 first, regenerate Inventory types
- **Task 1.1, live:** start the Compose stack and run `npm run seed`. Then call the gateway for the seeded inactive fixture "Xiaomi 14T, 512 GB":
  - with the manager's token: expect `200` with `description` and `status: NOT_ACTIVE`;
  - without a token: expect `404`;
  - with a malformed token: expect `401`.

  Record the result in *Live verification* of `README.md`, and set BL-01 to `Done` in its file and in the index row.
- If any check fails, BL-01 stays open as a `Blocker`. The tasks that read products with the token (group 3 and the journeys of group 5) stop until a person decides (`AGENTS.md` §4).
- **Task 1.2:** run `npm run gen:api`, then review the Inventory diff. Expect no code changes beyond the regenerated file, because `StockItem` is an alias. Confirm `npm run check` is green, so that every later commit starts green.
- Alternative: leave the Inventory drift to iteration 5. Rejected, because `npm run check` must pass before every commit.

### D2. API layer
- `getProduct(productId, { auth, signal })`: `auth` defaults to `'none'`, so the storefront callers stay unchanged. The manager pages pass `'session'`.
- New calls, typed by operationId and citing the EP number:
  - `updateProduct(productId, body)`, EP-CAT-02 `updateProduct`, returning `ProductDetails`;
  - `renameCategory(categoryId, name)`, EP-CAT-07;
  - `deleteCategory(categoryId)`, EP-CAT-08, with a `200` and an empty body.

  Check that `request()` resolves an empty `200` body, as it already does for `changeStatus`.
- The `activation-rejected` problem is read with `problemOf<Schemas['ActivationRejectedProblem']>(error, 'activation-rejected')`. Export the alias `ActivationRejected` from `catalog.ts`.
- **Type tests** pin:
  - the form field names to `ProductInput`;
  - the status filter values to the generated `status` enum of `browseProducts`.

### D3. Query keys
- Storefront keys stay `['catalog', …]` (`src/features/catalog/queries.ts`). Manager reads use their own root:
  - `manageKeys.products(state)` = `['manage', 'products', state]`;
  - `manageKeys.product(id)` = `['manage', 'product', id]`;
  - `manageKeys.categories(state)` = `['manage', 'categories', state]`.
- The product forms and filters use the existing `useCategories()` (`['catalog', 'categories']`, one page of 100). That query is public and the same for every caller.
- **After any successful manager write:** invalidate `['manage']` and `['catalog']`, so the storefront, the category names and the filters all refresh. After a `PUT`, the answer is also written into `manageKeys.product(id)`.
- Alternative: put the auth mode into the existing keys. Rejected, because two roots make "invalidate everything staff" and "never show staff data in the storefront" both a one-line prefix rule.
- The cache is already cleared on logout and login, so a manager's data never survives into another session.

### D4. Routes and navigation
- **Routes:** `manage` keeps `RequireRole allow="CATALOG_MANAGER"`, now with an `<Outlet />` and these children:
  - index → `<Navigate to="products" replace />`;
  - `products` → `ProductListPage`;
  - `products/new` → `ProductCreatePage`;
  - `products/:productId` → `ProductEditPage`;
  - `categories` → `CategoriesPage`;
  - `*` → `NotFoundPage`.
- The `AreaPlaceholderPage` stays for `/warehouse` and `/admin`.
- **Navigation:** the `CATALOG_MANAGER` entry in `AppLayout` changes from "Catalog console" to "Products" (`/manage/products`) and "Categories" (`/manage/categories`). The active link is highlighted for nested paths too, so `/manage/products/…` highlights "Products".
- `landingPath('CATALOG_MANAGER')` stays `/manage`, which redirects to the product list.
- Files go under `src/features/manage/`: pages, `url-state.ts`, `queries.ts`, `ProductForm.tsx`, `ProductStatusControl.tsx`, `CategoryDialog.tsx`, `ConfirmDialog.tsx` and `messages.ts`.

### D5. Product list
- **URL state:** `src/features/manage/url-state.ts` holds `{ search, category, status, sort, page }`, with `status` being `'ACTIVE' | 'NOT_ACTIVE' | null`.
  - It imports `PAGE_SIZE` and `SORT_OPTIONS` from the storefront module and reuses its parse rules: invalid values fall back to the defaults, and defaults are left out of the URL.
  - Alternative: add `status` to the storefront's `CatalogState`. Rejected, because the storefront would then parse a parameter Catalog ignores for customers.
- **Request:** `listProducts(query, { auth: 'session' })`. The price filters are not offered: a manager looks for products by name, category and status.
- **Table:** Mantine `Table` inside `Table.ScrollContainer` with `minWidth` of about 760 px, so a phone scrolls the table and not the page. The columns:
  - placeholder (`ProductImage`, 1:1, 40 px);
  - title (a link to the edit page);
  - category;
  - price (`Price`);
  - status (`StatusChip`);
  - stock (`StockBadge`);
  - actions ("Edit", plus `ProductStatusControl` in its compact form).
- Search is submitted with Enter or a button, as on the storefront. Changing a filter resets the page to 1.
- Loading shows skeleton rows. Failures use `ErrorState` with "Try again". The empty state is *Nothing matches*.

### D6. Product form
- One `ProductForm` (`@mantine/form`, as `ProfilePage` does) serves both create and edit. Its props are `initialValues`, `submitLabel`, `onSubmit` and `pending`.
- **Fields:**
  - title: `TextInput`;
  - description: `Textarea` with autosize and a character counter out of 5000;
  - category: `Select`, searchable, from `useCategories()`;
  - price: `NumberInput` with `decimalScale` 2, `min` 0.01, `hideControls`, and a "UAH" prefix label.
- **Client rules** come from *Product field rules*. Values are trimmed before sending. Server errors go through `applyServerErrors`.
- **Field names:** the schema's `categoryId` and `basePrice` are the form's field names too, so the server's `errors` keys match without mapping.
- **`422` `unprocessable-entity`:** set the category field error, then invalidate `['catalog', 'categories']`.
- **Create:** after `201`, navigate to `/manage/products/{productId}` with the router state `{ created: true }`. The edit page shows the "Product created…" notice from that state, and a reload does not repeat it.
- **Edit page:**
  - The form is filled from the product query once, when it first has data, and again only when the manager selects "Reload product". Background refetches, such as a window refocus or an invalidation after a status change, update the status panel but never overwrite typed values.
  - Alternative: re-key the form on every data change. Rejected, because it would silently drop edits.
- **Edit page layout:** two columns from 900 px, one column below.
  - Left: the form, with "Save changes" as the page's one primary button.
  - Right: a status panel on `surface-sunken` with the status and stock chips, the short id (`ShortId`), and the `ProductStatusControl`.
  - The "Changes saved" and "Product created…" notices use Mantine notifications.

### D7. Status changes
- **`ProductStatusControl`** has two forms: compact, for table rows, and full, for the edit panel. It wraps a mutation on `setProductStatus`. It shows "Activate" when the product is `NOT_ACTIVE` and "Deactivate" when it is `ACTIVE`, and it is disabled while the request runs.
- Deactivate opens `ConfirmDialog`, a small shared Mantine `Modal` like the one in `CancelOrder`. Activate needs no confirmation: it is safe to undo and checked by the server.
- **After `200`:** a notification ("Product activated" / "Product deactivated"), then the D3 invalidation. The new status shown is the one read back from the server, never assumed.
- **Errors:** `messages.ts` maps a status error to `{ message, conflict }`:
  - `activation-rejected`, by `reason`, through a table with the two documented codes and a fallback;
  - `409` `conflict` → the overlap message, with `conflict: true`;
  - `503` and everything else → `userMessage(error)`, which already adds the `Retry-After` wait.
- **Where errors appear:**
  - on the edit page, in a `danger-soft` alert in the status panel; a conflict adds "Reload product";
  - on the list, in one alert above the table that names the product ("Xiaomi 14T: …"), cleared by the next action. A conflict there refetches the list instead of offering a reload.
- Nothing is retried automatically (foundation: mutations never retry).

### D8. Overlapping saves
- A `409` on `PUT` or `PATCH` shows the overlap message in a page-level alert with the "Reload product" button.
- "Reload product" refetches `manageKeys.product(id)`, resets the form from the fresh data, and clears the alert.
- The entered values stay until then, so the manager can copy them.
- Catalog detects only transactions that overlap. A save over a stale page that does not overlap simply wins, which is the documented backend behaviour (Non-Goals).

### D9. Categories page
- **URL state:** `{ search, page }`. The request is `listCategories({ search, page: page - 1, size: 20 })` under `manageKeys.categories`.
- **Table:** name, short id, and the actions "Rename" and "Delete". "New category" is the primary button in the toolbar.
- **`CategoryDialog`:** a `Modal` with one `TextInput`, used for create and rename.
  - A `409` sets the name field error. A `400` goes through `applyServerErrors` on `name`.
  - A `404` on rename closes the dialog, shows "This category no longer exists." as an alert above the table, and invalidates.
- **Delete** uses `ConfirmDialog`, titled "Delete category "Audio"?".
  - `409` → an alert above the table with the "still has products" message;
  - `404` → handled like success, with the notice "Category deleted".
- After every successful write, the D3 invalidation runs.
- Showing a product count per category was considered and dropped: it costs one `browseProducts` call per row, and the `409` already explains the refusal.

### D10. Mock backend
- **`MockProduct.packaging`** becomes `DemoPackaging | null`. The fixtures keep their packaging.
- **New handlers** in `src/mocks/handlers.ts`. Every answer `satisfies` its generated schema, and every problem carries Catalog's `type` and `service: 'catalog-service'`:

  | Route | Gateway auth | Behaviour |
  |---|---|---|
  | `GET /catalog/products/:id` | `optional` | staff (`CATALOG_MANAGER`, `INVENTORY_WORKER`) see every status; others get `404` for inactive; bad token `401` |
  | `POST /catalog/products` | `CATALOG_MANAGER` | `400` `validation` with `errors` per field; `422` unknown category; `201 { productId }`; new product `NOT_ACTIVE`, `OUT_OF_STOCK`, `packaging: null`, `available: 0` |
  | `PUT /catalog/products/:id` | `CATALOG_MANAGER` | `404`, `400`, `422`, `200` full view |
  | `PATCH /catalog/products/:id/status` | `CATALOG_MANAGER` | `400` unknown status; `404`; `ACTIVE` with `packaging: null` → `422` `activation-rejected`, `reason: packaging_data_missing`; otherwise `200` |
  | `POST /catalog/categories` | `CATALOG_MANAGER` | `400` blank or longer than 100; `409` name in use, ignoring case; `201` |
  | `PUT /catalog/categories/:id` | `CATALOG_MANAGER` | `404`, `400`, `409` (another category has the name), `200` |
  | `DELETE /catalog/categories/:id` | `CATALOG_MANAGER` | `404`; `409` when a product references it; `200` |

- The overlapping-save `409` and the `503` from Inventory cannot happen in the mock by themselves. Tests produce them with `server.use(...)`.
- `stock_item_not_found` is not produced by the mock, because a mock product always has a stock record. A test covers it with `server.use`.

### D11. Browser journeys (`e2e/manage.spec.ts`, mock and live)
1. **Categories:** log in as the manager; land on `/manage/products`; open "Categories"; create "E2E category <timestamp>"; rename it; delete it. In live mode this leaves nothing behind.
2. **Draft product** (checks BL-01 end to end):
   - search for "E2E draft product"; if it is missing, create it in the fixture category "Accessories". In live mode it is created once and reused by later runs; in the mock it is created every run;
   - activate it and expect one of the two rejection messages: a just-created live product may not have its stock record yet;
   - change its description to include a timestamp and save;
   - reload the page and expect the new description, which shows that an inactive product loads with its description.
3. **Deactivate and reactivate:**
   - deactivate the active fixture product "TP-Link Archer AX55 Wi-Fi 6 router", which is in no demo cart and no other journey, and confirm;
   - the storefront search no longer lists it;
   - activate it again; the storefront lists it again.

   If a live run stops halfway, `npm run seed` activates it again.
- Also check: no horizontal page scroll at 375 px on the product list.

### D12. Tests
- **Unit:** the manager URL state (parse, serialize, the query it builds); the activation-reason table; the product and category field rules.
- **Type tests:** D2.
- **Mock handler tests:** every new route and status code through the API calls, including `401`/`403` and the staff visibility of EP-CAT-05 for each role.
- **Component tests:** named after the spec scenarios, through `renderApp('/manage/...')` with `loginAs('CATALOG_MANAGER')`. Conflicts and `503` are produced with `server.use`.

## Risks / Trade-offs

- **[BL-01 fails live]** → Task 1.1 runs first. A failure stops the token-dependent tasks before any UI is built on them.
- **[More than 100 categories]** → The forms and filters read one page of 100 (`useCategories`, as the storefront does). A missing category is still accepted by the server when it is typed, but it cannot be picked. That is acceptable at demo scale, and noted in the code.
- **[Live journeys leave catalog rows]** → Catalog has no hard delete, so the draft product is a fixed title reused across runs, and the test category is deleted at the end of its journey.
- **[Stale edit page without overlap]** → A save over values someone else changed earlier wins silently. This is the documented backend behaviour (no ETag), and is not hidden by a frontend check.
- **[Activation right after creation]** → On the live stack the stock record arrives asynchronously, through `ProductCreated` → Inventory. The `stock_item_not_found` message says to try again in a moment.
- **[A manager also browsing the shop]** → The storefront requests stay without a token (`catalog-browsing`), and the separate key roots (D3) keep the staff list out of the storefront.

## Migration Plan

New frontend code, regenerated Inventory types and a nullable mock packaging. There is no data to migrate. Rollback means reverting this change's commits. BL-01 moves to `Done` in `docs/backlog/` only after the live check in task 1.1.
