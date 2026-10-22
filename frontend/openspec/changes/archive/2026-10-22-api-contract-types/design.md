# Design

## Context

- The API layer is foundation design D4: one `request<T>()` in `src/api/http.ts` (the `/api` prefix, the token, `ApiError`, the session-expired signal, `Retry-After`), and one module per service with hand-written types citing the spec sections. Today there are three: `auth.ts`, `catalog.ts` and `inventory.ts` (the last one only for the seed script).
- The backend contract is now committed as `../<service>-service/openapi/public.json` (OpenAPI 3.1) for Auth, Catalog, Customer, Order, Inventory and Delivery. The rules are in `../docs/design/services-requirements/openapi-conventions.md` (D1–D9) and in BL-04's *Delivered* section. Those that shape this design:
  - **Paths are gateway paths** (`/catalog/products/{id}`), with one server `/`.
  - **Optional unless explicitly required (D5).** Optional properties may also arrive as `null`; nullability is never encoded.
  - **Problem schemas are named** (`Problem`, `ValidationProblem`, `CartRuleProblem`, …), media type `application/problem+json`. Where one status answers several schemas, the document uses `oneOf`, and the problem's `type` URI (`https://voltstore.com/errors/<kind>`) tells them apart. `400` may come without `errors`.
  - **operationIds are the controller method names** (`browseProducts`, `getProduct`, `listCategories`, `login`, `listStock`, …).
  - **Paging envelopes** are generated per element type (`PageResponseProductSummaryResponse`), always `{ content, page, size, totalElements }`, all required.
- What the documents show about the code already written:
  - `basePrice` is `number`, matching the live run of 2026-10-20, so the number-or-string conversion in `catalog.ts` is no longer needed.
  - Catalog lists now answer `size`; the mock handlers do not yet.
  - Auth's `CreateNonCustomerUserRequest.role` lists all four roles, `CUSTOMER` included; the service rejects it at runtime.
  - The mock `problem()` helper writes `type` kinds that differ from the backend's in places: `unprocessable`, where the backend writes `unprocessable-entity`.
- `customer-cart` (iteration 2) is proposed but not started. Its design D3 still plans a hand-written `src/api/customer.ts`.

## Goals / Non-Goals

**Goals:**
- Every request and response type in `src/api/` comes from a committed `public.json`. A change to one of those files breaks `npm run check` until the frontend is regenerated and adapted.
- Call sites keep the API they have. The pages, hooks, mocks and seed script change only where a type name or the paging envelope changed.
- Later iterations add a service call by writing a few lines on top of the generated types, never a DTO.

**Non-Goals:**
- Replacing `request()` or its error model (`ApiError`, kinds, messages).
- Runtime validation of answers against the schemas.
- Types for internal endpoints (`internal.json`), or for Payment and Notification.
- Changing any page's behaviour, apart from the two `api-access` rules in the delta spec (problem `type`, missing or `null` fields). The current pages already behave that way; the rules are written down for the pages that come next.

## Decisions

### D1. Generate types only, with `openapi-typescript`
- Add `openapi-typescript` (the current stable release, pinned exactly) as a dev dependency. It is the most widely used generator for TypeScript types. It reads OpenAPI 3.1, emits only types (`paths`, `components`, `operations`), and adds no runtime code.
- `request()` stays the one HTTP function. The service modules use generated types as the `T` of `request<T>()` and as their parameter types.
- *Alternative:* `openapi-fetch` as the client. Rejected. It would replace `request()`, which already carries the session-expired signal, `ApiError`, `Retry-After` and the test coverage of the foundation. Its one gain is checking path strings, and D3 gets that with a one-line helper.
- *Alternative:* full client generators (`orval`, `openapi-generator`). Rejected: they generate classes or hooks that compete with the existing TanStack Query setup, and they write far more code to review.

### D2. Committed output, plus a drift check in `npm run check`
- **`scripts/gen-api.ts`**, run with `tsx`:
  - It uses the generator's Node API for each of the six services, reading `../<service>-service/openapi/public.json` and writing `src/api/generated/<service>.ts`.
  - The output is the generator's own, with its "auto-generated, do not edit" header and the source path. Line endings are always LF.
- **Scripts:**
  - `npm run gen:api` writes the files.
  - `npm run check:api` generates in memory and fails when any committed file differs. It names the stale files and says to run `gen:api`.
  - `npm run check` runs `check:api` first.
- **Committed.** The generated files are committed: the build, the dev server and the iteration-7 Docker image (built from `frontend/` alone) then do not need the backend folders. `check:api` needs them, and it runs only in the monorepo.
- **Ignored by tooling.** ESLint and Prettier ignore `src/api/generated/`, so the generator's formatting is never rewritten.
- *Alternative:* generate on `postinstall` or before `build` without committing. Rejected: the frontend would then need the backend folders, and a backend change would turn into an unreviewed frontend diff.

### D3. Contract helpers: `src/api/contract.ts`
Small, generic, type-only helpers (plus one function), shared by every service module:

| Helper | Meaning |
|---|---|
| `JsonBody<Op>` | Request body of an operation (`application/json`) |
| `Query<Op>`, `PathParams<Op>` | Its query and path parameters |
| `Ok<Op>` | Its success body: the JSON content of its single `2xx` answer, or `void` when the answer has no content (`204`) |
| `Received<T>` | Deep: every optional property `p?: X` becomes `p?: Received<X> \| null`; required properties and arrays keep their shape |
| `pathFor(template, params)` | Takes a path template that the `paths` type of a service must contain (for example `'/catalog/products/{id}'`), fills it with URL-encoded values, and does not compile if the template is not in the document |

- Every response type that a service module exports is wrapped in `Received<…>`. TypeScript therefore makes code handle `null` wherever the contract allows a missing value (spec *Missing and null fields are treated alike*). Required fields keep their plain types, so the current pages need no fallbacks.
- `Received` is written against the shapes `openapi-typescript` emits. Its behaviour is pinned by type tests (`expectTypeOf`), so a generator upgrade that changes the shapes fails a test, not a page.

### D4. Service modules keep their names; types are aliases
- `auth.ts`, `catalog.ts` and `inventory.ts` keep every exported function with its signature.
- Their types become aliases of generated ones:
  - by operation, where the type is the input or output of one call, e.g. `ProductQuery = Query<operations['browseProducts']>`;
  - by schema name, where pages use the type on its own, e.g. `ProductSummary = Received<Schemas['ProductSummaryResponse']>`, `Category`, `StockItem`.
- Each function cites the spec EP number (the design, D9 of the conventions) and its `operationId` (the contract), and builds its path with `pathFor`.
- **Kept on the frontend, because the document is weaker or broader than the app needs:**
  - `ROLES` and `Role`: the app needs a runtime list. A type test checks that `Role` equals the generated role enum.
  - `SortBy`: the document types `sortBy` as a free string; the app offers four values.
  - `NonCustomerRequest`'s `role`: it excludes `CUSTOMER`, which the document allows.
- **Removed:** the `toPrice`/`Wire` conversion (Context).
- **One paging envelope.** `Page<T> = { content: T[]; page: number; size: number; totalElements: number }` sits in `contract.ts`. A type test checks it against two generated envelopes, Catalog's products and Inventory's stock. The service modules return `Page<ProductSummary>`, `Page<Category>` and `Page<StockItem>`; `StockPage` is removed.
- **Pager.** The storefront pager computes the page count from the answered `size` instead of the `PAGE_SIZE` constant (BL-05). The request still sends `PAGE_SIZE`.

### D5. Problems: generated shapes, narrowed by `type`
- **Shape.** `ProblemDetail` in `errors.ts` becomes `Received<ValidationProblem>` plus an index signature for extensions. The shared `Problem` and `ValidationProblem` schemas are identical in every document; Catalog's copy is used.
- **Narrowing.** A new function `problemOf<S>(error, kind)` returns the problem typed as `Received<S>`, but only when `error` is an `ApiError` whose `problem.type` is `https://voltstore.com/errors/<kind>`; otherwise `undefined`.
  - Service modules build named readers on it, the first being `cartViolation` in iteration 2 (`CartRuleProblem`, `cart-rule-violation`).
  - Pages never test for extension properties to guess the kind (spec *Several problem kinds on one status*). An unknown `type` falls back to the status handling that exists today.
- `applyServerErrors` already sends a `400` without `errors` to the general area; a test for the new scenario is added.

### D6. Mock backend follows the generated types
- **Typed answers.** Every JSON answer in `src/mocks/handlers.ts` is written as `… satisfies Schemas['…']` (or `Ok<operations['…']>`). A required field missing or `null`, or a field the schema does not have, then fails `tsc`.
- **Catalog lists** answer `size`.
- **`problem()`** takes an optional `type` kind. Its default kinds match the backend's (`validation`, `unauthorized`, `forbidden`, `not-found`, `conflict`, `unprocessable-entity`, `internal-error`), so extension problems in later iterations can be answered with their real `type` (`cart-rule-violation`, …). No current page depends on the `type` kind.
- `src/mocks/db.ts` keeps its own record types (they hold server-side fields such as `status` that some answers hide) and imports enums from the service modules.
- *Alternative:* `openapi-msw` to type handlers by path. Rejected for now: it is one more dependency on the MSW major version, and `satisfies` gives the same check on answers with no new API.

### D7. Conventions for later iterations
- **`AGENTS.md`:**
  - §3: API types come from `src/api/generated/`; the service modules only alias and call. Never hand-write a DTO.
  - §3: when `npm run check:api` fails, run `gen:api`, fix the compile errors, and re-check the mock handlers. If a generated type disagrees with the service spec, that is a backlog item (conventions D9), not a frontend adaptation.
  - §6: this change is iteration 1a, before `customer-cart`.
  - §7: `check` includes `check:api`.
- **`README.md`:** the two scripts and where the generated files come from.
- **`customer-cart`:**
  - D3 and task 1.1 are rewritten to build `src/api/customer.ts` on `generated/customer.ts`: `CartItem = Received<Schemas['CartItemResponse']>`, the seven calls by operationId, and `cartViolation` on `problemOf` with `cart-rule-violation`.
  - Its task 1.3 answers mock `422`s with that `type`.
- **Backlog:** BL-04 and BL-05 move to `Done`, with their last requirement ticked and the index rows updated.

## Risks / Trade-offs

- [The generator's emitted shapes change between major versions, and `Received`, `Ok` and `Query` depend on them] → exact version pin; the type tests of D3 and D4 fail on a shape change before any page does.
- [`openapi-typescript` declares a TypeScript peer range that may not include TypeScript 6] → it only needs the compiler API at generation time. If `npm install` refuses the peer range, pin the newest release that accepts it, or add an `overrides` entry for `typescript`; never `--force`. Record the choice in the commit body.
- [`check:api` fails for a frontend developer when the backend changes a document] → this is the purpose. The message names the command, and the regenerated diff shows the contract change to review.
- [operationIds are controller method names, so a backend rename breaks the alias] → it breaks at compile time with the operation name in the error, which is the cheapest place to find it.
- [Generated types describe the implementation, not the design (conventions D9)] → the Markdown specs stay the requirements source (`AGENTS.md` §3). A disagreement becomes a backlog item.
- [`Received` makes optional fields nullable, so later code needs `??` and `== null` checks] → intended. It is the spec rule, and today it affects no current page (every field they read is required).

## Migration Plan

- One change on the `frontend/openapi` branch: tooling first, then the modules, then the mocks, then the docs. `npm run check` and `npm run check:e2e` pass after each step.
- Before BL-04 and BL-05 are marked `Done`, run the live journeys (`npm run test:e2e:live`) against the Compose stack.
- Rollback: revert the change. Nothing outside `frontend/` depends on it.
