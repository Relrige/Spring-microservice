# Design

## Context

- **Earlier decisions hold:** iterations 1–3 are archived, and their decisions stay in force:
  - the API layer over the generated types;
  - TanStack Query, with mutations never retried;
  - `RequireRole`;
  - the MSW mock shared by every test level;
  - polling as the BL-13 workaround (`customer-checkout-orders` D8);
  - the date, short-id and status-word helpers in `src/components/` and `src/features/orders/status.tsx`.
- **Contracts:**
  - **Inventory** (`inventory-service/openapi/public.json`): `listStock`, `adjustStock` and `setPackaging` (`204`).
    - The stock list takes `search`, `packagingConfigured`, `productStatus`, `page`, `size` (at most 100) and `sortBy` (`title | availableQuantity | reservedQuantity | productId`, optionally `,asc`/`,desc`).
    - Its rows carry `title` and `productStatus`, both `null` until copied.
    - Adjust answers `422` for a write-off below zero and `503` when Catalog cannot be asked.
  - **Order:** `queue` → `{ deliveryDataAvailable, orders[] }`; `ship` → `409` (wrong state, or no TTN) or `503`.
  - **Delivery:** `retryTtnGeneration` → `200 { ttn }`, `409`, or `424` (`TtnGenerationFailedProblem` with `reason`, e.g. `packaging_data_missing:{productId}`).
- **Drift on main:** `npm run check:api` fails, because `src/api/generated/inventory.ts` predates Inventory tasks 22–24.
- **Mockup:** `docs/design/screens/WarehouseStock.dc.html`.
  - Header: "Stock" and "Fulfillment queue" with a count.
  - Stock table: product (title and short id), Catalog chip, available, reserved, total, packaging, and actions (Adjust, Set packaging).
  - Packaging filter chips, a sort, and the packaging dialog.
  - There is no mockup for the queue; it follows the design system README and the order page.
- **Backlog:** BL-02 is delivered and waits for verification. BL-03 is open, so stored packaging cannot be read. BL-13 is open: polling.

## Goals / Non-Goals

**Goals:**
- A worker can take a paid order to "Shipped" without the API, and fix what blocks it (stock, packaging, a failed TTN).
- The queue never shows an action the backend would refuse: ship only with a ready TTN, retry only for `TTN_FAILED`, neither without delivery data.

**Non-Goals:**
- Reading or pre-filling stored packaging values (BL-03).
- Choosing quantities per order or splitting shipments; Order ships whole orders.
- A history of shipped orders for staff (BL-10).

## Decisions

### D1. Inventory types first
Run `npm run gen:api` and review the Inventory diff. The existing aliases in `src/api/inventory.ts` (`StockItem`, `StockQuery`, `Packaging`) follow automatically. The seed's packaging and stock steps must still compile. This is task 1.1, so every later commit starts from a green check.

### D2. API layer
- `src/api/inventory.ts` gains nothing new beyond the regenerated aliases. Adjust's `422` and `503` are told apart by status, since both answer the plain `Problem` schema.
- `src/api/order.ts` gains `getFulfillmentQueue()` and `shipOrder(orderId)`, typed by operationId, with the aliases `FulfillmentQueue` and `QueueEntry`.
- New `src/api/delivery.ts`: `retryTtn(orderId)` (EP-DEL-03, `retryTtnGeneration`) and `ttnFailure(error)`. The latter returns the `reason` of a `ttn-generation-failed` problem via `problemOf`.

### D3. Routes and header
- **Routes:** `/warehouse` is the `StockPage`, `/warehouse/queue` the `QueuePage`, and `/warehouse/*` the not-found page, all under `RequireRole allow="INVENTORY_WORKER"`. The `AreaPlaceholderPage` for the warehouse goes away.
- **Header links:** the worker's links are "Stock" (`/warehouse`, exact match) and "Fulfillment queue" (`/warehouse/queue`).
- **Queue count:** `NavItem` gains an optional `count`, shown as a neutral badge (not `volt`: the design system allows no volt element in staff screens except the wordmark).
- **Count source:** the count comes from the `['fulfillment-queue']` query. In the header that query refetches every 30 s and on focus; on the queue page it refetches every 5 s (D7).
- **Landing:** stays `/warehouse` (no change to *Landing area per role*).

### D4. Stock page
- **URL state:** `useSearchParams` with `q`, `packaging` (`set|missing`), `status` (`ACTIVE|NOT_ACTIVE`), `sort` (`title|available|reserved`) and `page` (1-based).
  - The sort maps to `sortBy` `title,asc`, `availableQuantity,asc` or `reservedQuantity,desc`; no sort means the backend's `productId` order.
  - The query key is `['stock', params]`, the request size 20.
- **Filters and sort:** a `SegmentedControl` for packaging (as the mockup's chips), a `Select` each for status and sort, and a search field submitted with Enter or its button. A change of any of them resets `page`.
- **Table:** a Mantine `Table` in a `Table.ScrollContainer`, so 375 px scrolls inside the table and the page doesn't.
  - Columns as in the mockup.
  - The Catalog chip uses `StatusChip`: Active is `success`, Inactive `warning`, as in the mockup's colours.
  - "Missing" packaging is a `warning` chip.
  - A row without `title` shows the short id and "Title not available yet" (spec *Title not copied yet*). No Catalog call: BL-02 made the per-title lookup unnecessary.
- **Paging:** the existing `Paging` component.

### D5. Adjust dialog
- **Form:** a `Modal` with a `SegmentedControl` (Restock / Write off) and a quantity `NumberInput` (whole number, at least 1). It sends `delta = ±quantity`.
- **On success:** invalidate `['stock']` and close.
- **Errors:** `422` gives "Not enough available stock to write off", `404` "This product no longer exists in the catalog", and anything else `userMessage`. All of them keep the dialog open.

### D6. Packaging dialog
- **Form:** a `Modal` with four `NumberInput`s (whole numbers, at least 1, all required). It starts empty with the note "All four values are replaced together." and a `// BL-03:` comment that stored values cannot be read yet.
- **On `204`:** invalidate `['stock']` and close.
- **Errors:** a `400` with field errors is shown on the fields, through `applyServerErrors` mapping `grossWeightGrams` and the rest.

### D7. Fulfillment queue page
- **Query:** `['fulfillment-queue']` with `refetchInterval: 5000` while mounted (`// BL-13`). Hidden tabs don't poll.
- **Order cards:** one per order, in the answer's order.
  - Header: short id, "Paid" with the date, and the delivery status chip from `DELIVERY_STATUS`, or none when `null`.
  - Content: the items as `title × quantity`, and the delivery details.
  - TTN in `vs-mono` when present.
- **Actions:**
  - `TTN_GENERATED`: "Mark as shipped", with a confirmation modal naming the TTN.
  - `TTN_FAILED`: the failure reason and "Retry TTN".
  - `deliveryDataAvailable: false`: a warning notice at the top, and no actions.
- **Ship:**
  - `200`: notification "Order shipped", then remove the order from the cached queue and invalidate.
  - `409`: "This order changed; the queue was reloaded.", then invalidate.
  - Otherwise `userMessage`.
- **Retry:**
  - `200`: write the TTN and `TTN_GENERATED` into the cached entry and invalidate.
  - `424`: show the reason. `packaging_data_missing:{productId}` reads "{title} has no packaging data", with a link to `/warehouse?q={title}`. Any other reason shows as given.
  - `409`: invalidate.
- **Messages:** each card's own message area, so one order's error doesn't move the others.

### D8. Mock backend
- **`MockOrder`** gains `failureReason` and `shippedAt`.
- **Lazy progress** (as in `customer-checkout-orders` D10), applied when orders are read:
  - `PAID` for 3 s or more: when every ordered product has complete packaging, it becomes `PENDING_SHIPPING` with a TTN and `TTN_GENERATED`. Otherwise `deliveryStatus` becomes `TTN_FAILED` with `failureReason` `packaging_data_missing:{productId}`, and the order stays `PAID`.
  - `SHIPPED` for 30 s or more: it becomes `DELIVERED`, with `deliveryStatus` `DELIVERED`.
- **Stock rows** are derived from `db.products`:
  - `available` is `MockProduct.available`;
  - `reserved` is the quantity in orders `PENDING_PAYMENT`, `PAID` and `PENDING_SHIPPING`;
  - `total` is the sum of the two;
  - `title` and `productStatus` are copied at once;
  - `packagingConfigured` is true when all four values are above 0.

  `MockProduct.packaging` may be `null` (tests set it).
- **Handlers**, all `INVENTORY_WORKER` only:
  - stock list, with the filters, sorts (untitled last) and `400` for bad values;
  - adjust: `400` for a zero or decimal delta, `404` for an unknown product, `422` below zero; it recomputes `stockStatus`;
  - packaging: `400` with field errors, `204`;
  - queue: oldest `paidAt` first;
  - ship: `409` with `orderStatus` for the wrong state, or `409` without a ready TTN; on success `SHIPPED`, `IN_TRANSIT` and `shippedAt`;
  - TTN retry: `404`; `409` unless `TTN_FAILED`; then success, or `424` with the reason.

### D9. Tests and journeys
- **Unit and component tests:** named after the spec scenarios, through `renderApp` and the mock. Polling uses fake timers, as in iteration 3.
- **Updated existing tests:** the existing tests that expect the "Warehouse" placeholder heading (`navigation.test.tsx`, the staff-login journey) change to the stock page's "Stock" heading.
- **Playwright journey** (mock and live), one page session:
  1. A customer places and pays an order (sharing the checkout journey's steps through `e2e/support.ts`), then logs out.
  2. The worker logs in, opens the queue and waits for "TTN ready".
  3. The worker marks the order as shipped and sees it leave the queue.
  4. On the stock page, the worker searches the ordered product, restocks 1 and writes off 1, so live stock stays as seeded, and saves its packaging with the fixture values.
- **TTN retry:** covered by component tests only, because a TTN failure cannot be produced through the live API.

## Risks / Trade-offs

- **[The packaging form can overwrite good values with typos (BL-03)]** → The dialog says all four are replaced. The journey writes the fixture values, so live data stays seeded.
- **[Titles are eventually consistent]** → Rows without a title show the id. A renamed product matches the old title in a search for a moment, which the spec accepts.
- **[Live journeys ship real orders]** → Shipped orders leave stock reserved until Inventory consumes `OrderShipped`. `npm run seed` brings stock back to the fixture targets.
- **[Delivery's TTN may take longer than the 3 s of the mock]** → The live journey waits up to the polling interval plus Delivery's retry window (assertion timeout 30 s).

## Migration Plan

New frontend code and regenerated Inventory types; no data to migrate. BL-02 moves to `Done` in `docs/backlog/` after the live run shows titles in the stock list. Rollback means reverting this change's commits.
