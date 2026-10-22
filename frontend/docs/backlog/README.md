# Backend Backlog

Changes the **backend** should make so the frontend can work properly or better. The frontend owns this list; the backend team decides on each item and implements it through its own tasks in [`../../../docs/tasks/`](../../../docs/tasks/). The workflow (adding items, status and importance values, workaround markers in code) is described in [`../../AGENTS.md`](../../AGENTS.md#4-workflow-backend-backlog). New items start from [`_template.md`](_template.md).

| ID | Name | Status | Importance | Services | Blocks | Summary |
|---|---|---|---|---|---|---|
| [BL-01](BL-01-manager-reads-inactive-products.md) | Catalog managers can read any product by id | In progress | Blocker | catalog, gateway | iteration 4 | `GET /catalog/products/{id}` returns inactive products (full view) to staff. Catalog part done (task 27, PR #22); gateway task 12 (route `PUBLIC` → `OPTIONAL`) still open |
| [BL-02](BL-02-worker-product-titles.md) | Inventory workers can see product titles for stock rows | In progress | Blocker | catalog, inventory | iteration 5 | Backend done, not merged: Catalog tasks 27–29 (PR #22) and Inventory tasks 22–24 (PR #25): `GET /inventory/stock` returns `title` and `productStatus` with `search`, `productStatus` filter and `sortBy=title`. Waiting for frontend verification |
| [BL-03](BL-03-worker-reads-packaging.md) | Inventory workers can read current packaging values | Open | Important | inventory, gateway | iteration 5 | A worker-facing read of the stored packaging values (`null` when unset) so the form can be pre-filled |
| [BL-04](BL-04-openapi-contracts.md) | OpenAPI description for every routed service | Done | Important | auth, catalog, customer, order, inventory, delivery | — | Committed `<service>/openapi/public.json` files (PR #20); the frontend generates its types from them (`npm run gen:api`) |
| [BL-05](BL-05-uniform-paging-envelope.md) | One paging envelope for all list endpoints | Done | Nice to have | catalog | — | Catalog lists add `size`, matching Order and Inventory: `{ content, page, size, totalElements }` (PR #20; no `totalPages`) |
| [BL-06](BL-06-product-image-url.md) | Product image URL | Open | Nice to have | catalog | — | Optional `imageUrl` on products, returned by list and details; no upload |
| [BL-07](BL-07-order-submit-idempotency.md) | Idempotent order submission | Open | Important | order | — | `Idempotency-Key` header on `POST /orders` so a repeated submit returns the first order |
| [BL-08](BL-08-stale-customer-routing-docs.md) | Update stale Customer and gateway status notes | Open | Nice to have | docs | — | Specs still say Customer is not routed; the gateway routes all its endpoints |
| [BL-09](BL-09-account-management.md) | Account management features | Open | Later | auth, gateway | — | Password change/reset, email verification, user list, disabling staff, token revocation |
| [BL-10](BL-10-back-office-order-views.md) | Back-office order views | Open | Later | order, gateway | — | Staff list and details of all orders, including those needing manual attention |
| [BL-11](BL-11-carrier-list.md) | List of available carriers | Open | Later | order, gateway | — | Endpoint listing the carriers `POST /orders` accepts; frontend hardcodes `NOVA_POSHTA` until then |
| [BL-12](BL-12-product-attributes.md) | Product attributes | Open | Later | catalog | — | Technical characteristics and filtering by them; postponed |
| [BL-13](BL-13-order-status-push.md) | Push order status changes to the browser | Open | Later | order, gateway | — | SSE or long polling instead of short polling; postponed |
