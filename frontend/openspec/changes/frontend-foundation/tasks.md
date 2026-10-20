# Tasks

## 1. Project scaffold and tooling

- [x] 1.1 Scaffold a Vite React-TypeScript app in `frontend/` (keep `openspec/`, `docs/`, `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.claude/`, `.agents/`, `.gemini/`), strict `tsconfig`, pinned dependency versions and committed lockfile; verify `npm install` and `npm run build` succeed
- [x] 1.2 Add ESLint (typescript, react-hooks, jsx-a11y) and Prettier with `lint` and `format` scripts; verify `npm run lint` passes on the scaffold
- [x] 1.3 Add Vitest with jsdom and Testing Library, plus one smoke test rendering the app; verify `npm test` passes
- [x] 1.4 Add the `check` script (`tsc --noEmit` + lint + Vitest); verify `npm run check` passes and finishes in under a minute
- [ ] 1.5 Configure the Vite dev proxy `/api` → `VITE_API_TARGET` (default `http://localhost:8080`) with the prefix stripped (design D2); verify with the Compose stack running that `/api/catalog/categories` on the dev server returns the gateway's answer
- [x] 1.6 Add Mantine (core, hooks, form, notifications) with a theme generated from `docs/design/design-system/tokens.json` (design D16: colours as CSS variables for light and dark, Inter, radius, spacing), and the TanStack Query client with the retry rules of design D3; verify the smoke test still passes and the colour-scheme toggle switches light/dark

## 2. API access layer (spec `api-access`)

- [x] 2.1 Implement `src/api/http.ts` and `src/api/errors.ts`: `/api` prefix, optional bearer token with per-call opt-out, JSON handling, `ApiError` kinds, problem-details parsing including non-JSON bodies and `Retry-After`; verify with unit tests for each status class, a non-JSON body and a network failure
- [x] 2.2 Implement the session-expired signal for `401` on token-carrying requests, with the login call excluded; verify with unit tests for both cases
- [x] 2.3 Add shared UI pieces `ErrorState` (message plus "Try again"), `Loading`, and a helper that puts `400` `errors` onto Mantine form fields with the rest in a general error area; verify with component tests for the scenarios of *Field validation errors on forms*, *Unavailable backend* and *Unexpected errors*
- [x] 2.4 Write `src/api/auth.ts` and `src/api/catalog.ts` types and calls mirroring the Auth and Catalog specs (EP-AUTH-01/02, EP-CAT-04/05/10), each type citing its spec section; accept `basePrice` as number or string; verify with `npm run check`

## 3. Mock backend (design D10)

- [x] 3.1 Create MSW handlers and an in-memory db for register, login, product list, product details and categories, following the specs' status codes and problem details (inactive → `404`, duplicate email → `409`, `minPrice > maxPrice` → `400`, `400` field errors); verify with Vitest tests that call the handlers through `http.ts`
- [x] 3.2 Create the shared demo fixtures `src/fixtures/demo-data.ts` (design D14: one account per role, 4 categories, about 45 products with description, price, packaging, target stock and active flag; some out of stock, some inactive) and start the mock db from them; verify through a handler test (inactive ones hidden, out-of-stock ones listed)
- [x] 3.3 Add `npm run dev:mock` (MSW service worker when `VITE_API_MOCK=true`, port 5174); verify the app loads the mocked product list in the browser with no backend running
- [x] 3.4 Wire MSW into the Vitest setup so component tests use the same handlers; verify the smoke test runs with handlers active

## 4. User session (spec `user-session`)

- [x] 4.1 Implement token decoding and validation (claims, known roles, expiry) as pure functions; verify with unit tests for a valid token, a missing `email` claim, an unknown role, an unreadable payload and an expired token
- [x] 4.2 Implement `SessionProvider`: `localStorage` persistence, expiry timer, cross-tab `storage` sync, logout clearing the query cache, the expired-session redirect with a safe `returnTo`; verify with tests for reload, expiry while idle, logout in another tab and rejection of an external `returnTo`
- [x] 4.3 Build the login page (`/login`) as in the mockup (design D16): form, `401` message, expired-session notice, redirect to `returnTo` or the role's landing area; verify with component tests for the *Login* scenarios
- [x] 4.4 Build the registration page (`/register`): client-side checks, `409` message with a login link, automatic login after `201`; verify with component tests for the *Customer registration* scenarios

## 5. Application shell and navigation (spec `app-navigation`)

- [x] 5.1 Set up the router with storefront routes, the four role areas behind `RequireRole`, `roleHome()`, and placeholder landing pages for `/manage`, `/warehouse`, `/admin` naming the iteration that adds each area; verify with tests for anonymous redirect, wrong-role access-denied, a return page not allowed for the role, and `ADMIN` denied on `/manage`
- [x] 5.2 Build the AppShell header as in the mockups (design D16): role-aware links, user email and role, logout, burger menu below the desktop breakpoint; verify with component tests for the visitor and manager headers
- [x] 5.3 Add the not-found and access-denied pages; verify with tests for `/no-such-page` and a customer opening `/admin`

## 6. Catalog browsing (spec `catalog-browsing`)

- [x] 6.1 Implement the catalog URL state parse and serialize functions (design D8: 1-based `page`, defaults, invalid values fall back); verify with unit tests including `page=-3`, an unknown sort, and round-tripping
- [x] 6.2 Build the product list page as in the mockup (design D16): tokenless requests, search, category select (from categories; failure tolerated), price range validation, sort, paging with total count, empty state, loading and error states; verify with component tests for each *Product list*, *Search and filters*, *Sorting*, *Paging* and *List state in the URL* scenario
- [x] 6.3 Build the product details page as in the mockup (design D16): title, description, price, category name (omitted if unknown), stock badge, "not available" page for `404`; verify with component tests for active, inactive and non-UUID ids
- [x] 6.4 Add the shared `Price` (design D9) and `StockBadge` components; verify `Price` formats `1299` as `UAH 1,299.00` in a unit test
- [x] 6.5 Add `ProductImage` placeholders (design D15) and use them in the list and details pages; verify with component tests for *Same category, same placeholder* and *Unknown category*

## 7. Demo seed script (design D14)

- [ ] 7.1 Add `scripts/seed.ts` and `npm run seed` (`tsx`; `SEED_API_URL`, `SEED_ADMIN_EMAIL`, required `SEED_ADMIN_PASSWORD` with a clear error when missing): admin login, staff and demo customer accounts (`409` = exists), categories and products found or created as the manager; verify against the Compose stack that the accounts can log in and the manager's list contains every fixture product
- [ ] 7.2 Add the worker and activation steps: packaging, stock adjusted to target by difference, activation with `503` retries, then polling the storefront until stock statuses match (60 s limit) and printing a summary; verify the storefront shows the expected active products with the expected stock labels
- [ ] 7.3 Make reruns safe: a second run creates nothing, adjusts nothing and reports "unchanged"; verify by running the script twice and comparing the manager's product count and the stock list

## 8. Browser tests and agent workflow

- [x] 8.1 Set up Playwright with a `mock` project (starts `dev:mock`) and a `live` project (`E2E_LIVE=1`, gateway on `:8080`); add `check:e2e`; verify `npm run check:e2e` passes with no Docker running
- [x] 8.2 Write browser journeys: browse, filter, page and open a product; register, log out, log in again; staff login lands in its area; expired session returns to the same page; layout at 375 px has no horizontal scroll; verify they pass in the `mock` project
- [ ] 8.3 Run the same journeys in the `live` project against the Compose stack after `npm run seed`; fix any mock or API-type mismatch found and confirm the `basePrice` format (design, *Open Questions*); verify the `live` run passes and record the result in `frontend/README.md`
- [x] 8.4 Add `.claude/launch.json` entries `frontend-dev` (5173) and `frontend-mock` (5174); verify both start from the preview pane
- [ ] 8.5 Complete the *Checks* section of the existing `frontend/AGENTS.md` with the real commands, and write `frontend/README.md` (scripts, ports, demo accounts, `npm run seed` with its variables, live test setup); verify every command in the README runs as written

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive` so the four capability specs become the main specs under `openspec/specs/`.
- Propose iteration 2 (`customer-cart`) from the iteration plan.
