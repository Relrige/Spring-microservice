# Proposal

## Why

The backend now commits an OpenAPI document per routed service (`<service>/openapi/public.json`, PR #20 merged on 2026-10-22, BL-04), and Catalog lists answer the uniform paging envelope with `size` (BL-05). The frontend still writes its request and response types by hand from the Markdown specs, so a backend change can break it silently. Switching now, before iteration 2 (`customer-cart`) adds the first Customer calls, means every later iteration starts from generated types instead of adding more hand-written ones.

## What Changes

- Add `openapi-typescript` and a script `npm run gen:api` that generates one TypeScript file per service (Auth, Catalog, Customer, Order, Inventory, Delivery) from `../<service>-service/openapi/public.json` into `src/api/generated/`. The generated files are committed, so the build and the Docker image do not need the backend folders.
- Add `npm run check:api`, which fails when the committed generated files differ from what the current `public.json` files produce, and run it as part of `npm run check`. A backend change to a public document then shows up as a failing frontend check until the types are regenerated and the frontend adapted.
- Add small type helpers in `src/api/contract.ts`: request body, query, path parameters and success body of an operation by its `operationId`, a `Received<T>` type that widens optional response fields to `T | null | undefined` (the documents never encode `null`, D5), and the problem schemas.
- Rebuild `src/api/auth.ts`, `src/api/catalog.ts` and `src/api/inventory.ts` on the generated types, keeping their exported function names and call signatures. Drop the hand-written DTO interfaces; keep short aliases where the generated names read badly (`ProductSummary` for `ProductSummaryResponse`).
- Use one generated paging envelope for every service. Catalog's `Page` now includes `size` (BL-05); the storefront pager takes the page size from the answer.
- Drop the `basePrice` number-or-string conversion: the document says `number`, and the live run of 2026-10-20 confirmed Catalog sends a JSON number.
- Type the problem details from the generated schemas, and add a helper that narrows an `ApiError` to one problem schema by its `type` URI, for statuses that answer several schemas with `oneOf` (`422` of `POST /orders`, `409` of `pay` and `ship`).
- Type the MSW mock answers against the generated schemas (`satisfies`), add `size` to the Catalog list answers, and make sure mocks never send `null` for a required field.
- Update the conventions for later iterations: `AGENTS.md` §3 (types come from `src/api/generated/`, never hand-written; regenerate when a `public.json` changes), `README.md` (the new scripts), the `customer-cart` change's design D3 and task 1.1, and mark BL-04 and BL-05 `Done`.
- No visible change for users of the existing pages.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `api-access`: the problem-details requirement now says how a page tells apart several problem kinds answered with the same status (by `type`) and how a `400` without `errors` is shown; a new requirement says that a missing field and a `null` field in an answer are treated alike.

## Impact

- **Code:** `src/api/*` (all three service modules, `errors.ts`, new `contract.ts` and `generated/`), `src/mocks/handlers.ts` and `db.ts`, `src/features/catalog/` (pager page size, `ProductTile` and `StatusChip` type imports), `scripts/seed.ts` (type imports only).
- **Tooling:** new dev dependency `openapi-typescript`; new scripts `gen:api` and `check:api`; ESLint and Prettier ignore `src/api/generated/`.
- **Docs and plans:** `AGENTS.md` §3 and §6, `README.md`, `docs/backlog/` (BL-04, BL-05, index), `openspec/changes/customer-cart/design.md` and `tasks.md`.
- **Backend:** none. The frontend only reads the committed `public.json` files; it never reads `internal.json` or calls `/v3/api-docs`.
- **Order of iterations:** this change is applied before `customer-cart`, so the cart's Customer calls are written on generated types from the start.
