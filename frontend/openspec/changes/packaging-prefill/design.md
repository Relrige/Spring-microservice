# Design

## Context

- `PackagingDialog` (`src/features/warehouse/StockDialogs.tsx`) opens from a stock row and replaces all four values with `PUT …/packaging`. It starts empty because of BL-03 (comment in the code).
- Inventory EP-INV-08 (`getStockItem`, PR #37) answers the stock row plus `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`, each missing while not set; `404` without a stock item. The gateway routes it for `INVENTORY_WORKER` (PR #38).

## Goals / Non-Goals

**Goals:** the dialog shows what is stored; the seed keeps packaging equal to the fixtures.
**Non-Goals:** showing packaging in the stock list itself; editing a single value without sending all four (the `PUT` replaces all four).

## Decisions

### D1. The dialog reads when it opens
- A query keyed `['stock', productId]` (under the existing `stockKey`, so saving invalidates it with the list) runs while the dialog is open.
- When the data arrives, the form is set once (`setValues` + `resetDirty`) to the stored values, `''` for missing ones; values the worker typed meanwhile are not overwritten (the form is filled before it is shown).
- Loading: the fields are replaced by the app's loading placeholder. Error: `ErrorState` with "Try again" (as elsewhere). The save button only appears with the form.

### D2. Seed
- For each fixture product with configured packaging, the seed reads EP-INV-08 and sets packaging only when a value differs from the fixture; `packaging set` counts these. Unconfigured packaging is set as before.

### D3. Mock
- `GET /inventory/stock/:productId` for workers: the stock row as in the list handler plus the four values (omitted when packaging is `null`); `404` problem for an unknown product.

## Risks / Trade-offs

- **[#37/#38 merged separately]** → this branch builds on both; the PR targets `main` after they are merged.
