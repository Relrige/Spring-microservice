# Tasks

## 1. Type generation

- [x] 1.1 Add `openapi-typescript` (current stable, exact pin; design Risks for the TypeScript peer range), `scripts/gen-api.ts` and `npm run gen:api` (design D2): six files `src/api/generated/{auth,catalog,customer,order,inventory,delivery}.ts` from `../<service>-service/openapi/public.json`, LF line endings, never `internal.json`; add `src/api/generated/` to the ESLint ignores and `.prettierignore`; verify the six files are generated, contain `paths`, `components` and `operations`, and `npm run check` passes
- [x] 1.2 Add `npm run check:api` (generate in memory, compare with the committed files, name each stale file and the fix) and run it first in `npm run check`; document both scripts and the source of the generated files in `README.md`; verify that `check:api` fails naming `catalog.ts` after a local edit to `src/api/generated/catalog.ts`, passes again after `gen:api`, and that `npm run check` passes

## 2. Contract helpers and problems (spec `api-access`)

- [x] 2.1 Write `src/api/contract.ts` (design D3, D4): `JsonBody`, `Query`, `PathParams`, `Ok` (`void` for a `204` such as `setPackaging`), `Received`, `pathFor` and `Page<T>`; verify with type tests (`expectTypeOf`) that an optional property becomes `T | null | undefined` while required ones keep their type (scenarios *Optional field answered as null*, *Required fields present*), that `Page<…>` equals the generated Catalog product and Inventory stock envelopes, that an unknown path template is a compile error (`@ts-expect-error`), and with unit tests that `pathFor` fills and URL-encodes parameters
- [x] 2.2 Base `ProblemDetail` in `src/api/errors.ts` on the generated `ValidationProblem` and add `problemOf` (design D5); verify with unit tests for scenario *Several problem kinds on one status* (an Order `422` with `type` `…/insufficient-stock` read as `InsufficientStockProblem`, not as `IneligibleItemsProblem`), scenario *Unknown problem type* (no match → `undefined`, and `userMessage` falls back to the status message), a non-`ApiError`, and a component test for scenario *Bad request without field errors* through `applyServerErrors`

## 3. Service modules on generated types

- [x] 3.1 Rebuild `src/api/auth.ts` on `generated/auth.ts` (design D4): aliases, `pathFor`, operationIds cited next to the EP numbers, `ROLES` kept with a type test that `Role` equals the generated role enum, and `NonCustomerRequest` still excluding `CUSTOMER`; verify `npm run check` passes with the existing login and register tests unchanged
- [x] 3.2 Rebuild `src/api/catalog.ts` on `generated/catalog.ts`: aliases (`ProductSummary`, `ProductDetails`, `Category`, `ProductQuery`, `ProductInput`, `ProductStatus`, `StockStatus`), `SortBy` kept, `toPrice`/`Wire` removed, lists returning `Page<T>` with `size`; add `size` to the mock Catalog list answers, and take the storefront page count from the answered `size` (BL-05); verify `catalog.test.ts` and the handler tests pass, and a component test where the mock answers `size: 10` with `totalElements: 25` shows three pages
- [ ] 3.3 Rebuild `src/api/inventory.ts` on `generated/inventory.ts` (`StockItem`, `StockQuery`, `Packaging`, `Page<StockItem>` instead of `StockPage`, `setPackaging` typed `void`) and adjust the type imports of `scripts/seed.ts`; verify `npm run check` passes and `npx tsx scripts/seed.ts --help` (or a run without `SEED_ADMIN_PASSWORD`) starts and reports its usage or missing-variable error instead of a type or import error

## 4. Mock backend on generated types

- [ ] 4.1 Type every JSON answer in `src/mocks/handlers.ts` with `satisfies` against the generated schemas, align the default `type` kinds of `problem()` with the backend's (`unprocessable-entity`, `internal-error`, …) and add its optional `type` kind parameter (design D6); keep `db.ts` record types with enums imported from the service modules; verify `npm run check` passes, a handler test asserts the `type` URI of a `400` and of a custom kind, and removing a required field from one answer makes `tsc` fail (checked once, not committed)

## 5. Conventions for later iterations

- [x] 5.1 Update `AGENTS.md` (design D7): §3 types come from `src/api/generated/` and are never hand-written, what to do when `check:api` fails, generated-vs-spec disagreements are backlog items; §6 add iteration 1a `api-contract-types` before `customer-cart`; §7 `check` includes `check:api`; verify the three sections name `gen:api`, `check:api` and `src/api/generated/`, and no section still says types are hand-written
- [x] 5.2 Update the `customer-cart` change: design D3 builds `src/api/customer.ts` on `generated/customer.ts` (`CartItem` alias, calls by operationId, `cartViolation` on `problemOf` with `cart-rule-violation`); task 1.1 says the same; task 1.3 answers mock `422`s with the `cart-rule-violation` type; verify `openspec validate customer-cart` passes and neither file still plans hand-written cart types

## 6. Integration

- [ ] 6.1 Run `npm run check` and `npm run check:e2e`; verify both pass
- [ ] 6.2 Start the Compose stack, run `npm run seed` and `npm run test:e2e:live`, and record the result in the *Live verification* section of `README.md` (including that Catalog lists answer `size`); verify the live run passes
- [ ] 6.3 Mark BL-04 and BL-05 `Done` (last requirement ticked, *Frontend impact* states what was done, frontmatter and index row equal) and remove the BL-04/BL-05 workaround notes from code comments if any remain (`grep -rn "BL-0[45]" src scripts e2e`); verify the index rows read `Done` and the grep finds no workaround marker

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive` so the `api-access` delta is merged into the main spec.
- Apply `customer-cart` (iteration 2) on top of this change.
