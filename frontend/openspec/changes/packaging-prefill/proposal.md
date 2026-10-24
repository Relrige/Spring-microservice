# Proposal

## Why

The worker's packaging form always opens empty, because workers could not read the stored packaging values (BL-03). A worker who wants to correct one dimension has to re-enter all four and cannot see what is stored. Inventory now offers a worker read of one stock item with its packaging (Inventory task 25, PR #37; gateway route, task 13, PR #38).

## What Changes

- **Packaging dialog:** opens with the stored values (from `GET /inventory/stock/{productId}`), empty fields where a value is not set. While the values load, the dialog shows a loading state; if the read fails, it shows the error with "Try again".
- **Seed:** `npm run seed` reads each fixture product's packaging and sets it only when it differs from the fixture, instead of leaving configured packaging untouched (the BL-03 workaround is removed).
- **Mock backend:** a handler for the new read.
- **Backlog:** BL-03 becomes `Done` after the live check.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `warehouse-stock`: *Set packaging* starts from the stored values instead of an empty form.

## Impact

- **Depends on** #37 and #38 (`npm run gen:api` picks up Inventory's `getStockItem`).
- **Changed code:** `src/api/inventory.ts`, `src/api/generated/inventory.ts`, `src/features/warehouse/StockDialogs.tsx`, `src/mocks/handlers.ts`, `scripts/seed.ts`, tests.
