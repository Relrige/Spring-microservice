# Proposal

## Why

Inventory workers can log in, but `/warehouse` is a placeholder, so stock, packaging and dispatch are never shown, and paid orders can only reach "Shipped" through the API. The backend now offers everything the worker needs through the gateway: the stock list (EP-INV-01, with titles since BL-02), adjust and packaging (EP-INV-02/03), the fulfillment queue and dispatch (EP-ORD-07/08), and the manual TTN retry (EP-DEL-03). This iteration builds the worker console and completes the order's way from payment to the parcel.

## What Changes

- **Inventory types brought up to date.** Inventory tasks 22–24 changed `inventory-service/openapi/public.json` (stock rows now carry `title` and `productStatus`, and the list takes `search`, `productStatus` and `sortBy=title`). `npm run check:api` fails on main because the generated types were not regenerated; they are regenerated first.
- **Stock page** (`/warehouse`), as in `WarehouseStock.dc.html`:
  - one row per stock item with its title (or the product id while the title is not copied yet), the Catalog status, available, reserved and total, and whether packaging is set;
  - search by title, the packaging filter (all, set, missing), a status filter and sorting;
  - 20 rows per page, with the filters in the URL.
- **Adjust stock:** a dialog to restock or write off a quantity. A write-off below zero (`422`) and an unavailable Catalog (`503`) are shown in plain words.
- **Set packaging:** a dialog with the four values, replaced together. The form starts empty, because workers cannot read stored packaging yet (BL-03, workaround).
- **Fulfillment queue** (`/warehouse/queue`):
  - the paid orders waiting for dispatch, oldest payment first;
  - each with its items, delivery details, delivery status, TTN and failure reason;
  - polled every 5 s while visible (BL-13, workaround);
  - a notice when Delivery is unavailable.
- **Dispatch:** "Mark as shipped" for an order whose TTN is ready, after a confirmation. A `409` (no TTN yet, or the order changed) reads the queue again.
- **TTN retry:** "Retry TTN" for a shipment in `TTN_FAILED`. A failed retry (`424`) shows the reason. Missing packaging names the product and links to its stock row.
- **Navigation:** the worker header shows "Stock" and "Fulfillment queue" with the number of orders waiting.
- **Mock backend:** handlers for the stock list, adjust and packaging, the fulfillment queue, ship and TTN retry.
  - Stock rows are derived from the mock products and their reservations.
  - A TTN fails when an ordered product has no complete packaging, so the retry can be shown.
  - Shipping moves the order to `SHIPPED`, and `DELIVERED` follows later.
- **Browser journeys:** a customer pays an order; the worker sees it in the queue, ships it, and restocks and sets packaging on the stock page. Against the mock and the live stack.

Not in this change: reading stored packaging values (BL-03), push updates (BL-13; polling stands in), staff views of all orders (BL-10).

## Capabilities

### New Capabilities
- `warehouse-stock`: the worker's stock page — stock rows with copied product data, search, filters and sorting, adjusting stock, and setting packaging.
- `fulfillment-queue`: the worker's dispatch work — the queue of paid orders with their delivery state, marking an order as shipped, and retrying a failed TTN.

### Modified Capabilities
- `app-navigation`: *Role-aware navigation* — the inventory worker's header links are "Stock" and "Fulfillment queue", the latter with the number of orders waiting.

## Impact

- Code under `frontend/` only; no service, gateway or spec changes. BL-02 moves to `Done` once the live run shows titles in the stock list. BL-03 and BL-13 stay `Open`, with workarounds marked in code.
- Gateway routes used by the app, all of them `INVENTORY_WORKER`:
  - `GET /inventory/stock`, `POST /inventory/stock/{productId}/adjust`, `PUT /inventory/stock/{productId}/packaging`;
  - `GET /orders/fulfillment-queue`, `POST /orders/{orderId}/ship`;
  - `POST /delivery/orders/{orderId}/ttn/retry`.
- New and changed code:
  - `src/api/delivery.ts` (new);
  - queue and ship calls in `src/api/order.ts`;
  - the stock aliases in `src/api/inventory.ts`;
  - a `warehouse` feature folder;
  - mock handlers;
  - new journeys.
- Regenerated `src/api/generated/inventory.ts`.
- The placeholder `/warehouse` page goes away. Tests and the staff-login journey that expect its "Warehouse" heading change to the stock page.
