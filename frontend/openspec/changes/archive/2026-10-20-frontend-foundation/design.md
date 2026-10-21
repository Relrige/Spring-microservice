# Design

## Context

- `frontend/` contains OpenSpec scaffolding, `AGENTS.md` (agentic working agreement) and the backend backlog `docs/backlog/`; there is no application code yet.
- The backend is reached only through the API Gateway (Compose: `http://localhost:8080`; Kubernetes: the Ingress sends `/` unchanged to the gateway). The gateway has **no CORS** (deferred in its spec), verifies HS256 JWTs itself, strips identity headers, and answers unknown routes with `404`.
- All services answer errors as RFC 9457 problem details (`application/problem+json`, `type` = `https://voltstore.com/errors/<kind>`) with service-specific extensions (`errors`, `violation`, `reason`, `ineligibleItems`, `insufficientItems`, …).
- No service publishes OpenAPI yet (backlog BL-04). The contracts are the Markdown specs in `../docs/design/services-specs/` and `../docs/design/services-requirements/api-contracts.md`.
- Tokens live 1 hour; there is no refresh token and no logout endpoint.
- The frontend is developed **fully by coding agents**: it is not a study goal (see proposal). Every decision below favours what an agent can build, check and change reliably without a person: well-known libraries, strong typing, fast automated checks, and a backend mock so the UI runs without the 20-container stack.

## Goals / Non-Goals

**Goals:**
- A project skeleton that later iterations extend without restructuring: feature folders, one API layer, one session model, one routing scheme for the four role areas.
- Agent feedback loops: `npm run check` (types, lint, unit and component tests) in well under a minute; browser tests against a mock backend that need no Docker; the same browser tests runnable against the real stack.
- The first usable slice: login, registration and storefront browsing (see specs).

**Non-Goals:**
- Custom components beyond what the design artifacts (D16) and Mantine need; real product images (Catalog has none, BL-06).
- Server-side rendering, internationalisation (UI text is English only), offline support, analytics.
- Container image and Kubernetes manifests (iteration 7).
- Any change to services, the gateway or their specs.

## Decisions

### D1. Stack: React + TypeScript (strict) + Vite
Chosen by the team. React has the largest body of examples, which matters most when agents write all the code. Vite gives a fast dev server with a built-in proxy (D2) and a build with no extra configuration. Pin exact versions in `package.json` and commit `package-lock.json`.

### D2. Same-origin `/api` prefix, stripped by a proxy
The browser calls `/api/<gateway path>`; a proxy removes `/api` and forwards to the gateway.
- **Dev:** Vite `server.proxy['/api']` → `VITE_API_TARGET` (default `http://localhost:8080`), `rewrite: p => p.replace(/^\/api/, '')`.
- **Later deployment (iteration 7):** an nginx container serves the built files; a host-specific Ingress rule (for example host `shop.localhost`) sends `/api(/|$)(.*)` to `api-gateway` with a rewrite and `/` to the frontend. The current host-less rule `/` → gateway stays as it is, so API clients and runbooks that call the gateway through the Ingress keep working, and the gateway's NetworkPolicy (only the ingress controller may reach it) is unchanged.
- **Why a prefix:** SPA paths and API paths overlap (for example a future `/orders` page vs. the `GET /orders` API). A prefix separates them without listing API routes in the proxy.
- **Alternatives:** CORS on the gateway (needs a gateway change and a second origin; rejected); proxying listed API prefixes without `/api` (collides with SPA routes; rejected).

### D3. Server state with TanStack Query; no global store
All backend data is server state: fetched, cached, refetched and invalidated per query key. TanStack Query handles loading/error states, retries for reads, and cache invalidation after writes. Mutations are never retried automatically (spec `api-access`, *Unavailable backend*). Read queries retry once, and only for network errors and `502/503/504`.
The only client state is the session (D5) and, from iteration 2, the guest cart; both live in React context backed by `localStorage`. No Redux or Zustand.

### D4. One API layer with hand-written, spec-derived types
- `src/api/http.ts`: one `request()` function: prefixes `/api`, adds `Authorization` unless the call opts out (`auth: 'none'` for storefront catalog calls, spec `catalog-browsing`), sends/parses JSON, and turns every non-2xx answer or network failure into an `ApiError { kind, status, problem?, retryAfter? }`. `kind` ∈ `validation | unauthorized | forbidden | not-found | conflict | unprocessable | payment | unavailable | network | unexpected`.
- A `401` on a request that carried a token raises a session-expired signal (D5). The login call opts out, so its `401` reaches the form (spec `api-access`).
- `src/api/<service>.ts` per backend service (`auth`, `catalog`, later `customer`, `orders`, `inventory`, `delivery`) with request/response types copied from the service specs. Each file states which spec section it mirrors, so an agent can re-check a contract.
- **Alternative:** generating types from OpenAPI. No service publishes OpenAPI yet; backlog item BL-04 asks for it, and the hand-written types are replaced by generated ones once it is done.

### D5. Session model
- Token stored in `localStorage` (`voltstore.session`). The payload is base64url-decoded (no signature check; the gateway verifies it) into `{ userId, role, email?, expiresAt }`.
- A timer ends the session at `exp`; a `storage` event listener keeps tabs in sync; logout clears the TanStack Query cache.
- The session-expired signal (D4) and expiry use the same path: clear the session, remember the current location, and navigate to `/login?expired=1&returnTo=…`. `returnTo` accepts only same-app relative paths (no open redirect).
- **Trade-off:** `localStorage` can be read by injected scripts (XSS). This is accepted for a demo app with a 1-hour token and no third-party scripts. An httpOnly cookie would need a backend session and is out of scope.

### D6. Routing: React Router with area layouts and guards
Route tree: storefront (`/`, `/products/:productId`, `/login`, `/register`), then `/account/*`, `/manage/*`, `/warehouse/*`, `/admin/*`, each under a guard element `<RequireRole role=…>`, and a catch-all not-found route. One `roleHome(role)` function gives the landing paths (spec `app-navigation`). In this change the staff landing pages (`/manage`, `/warehouse`, `/admin`) are placeholders saying which iteration adds the area; customers have no `/account` pages yet.

### D7. UI components: Mantine
Mantine (core, hooks, form, notifications) supplies an AppShell with a responsive burger navigation, accessible inputs, modals, tables, pagination and toasts. Agents can then build complete screens from well-documented components without writing CSS, and the 360 px requirement is met by the shell. The theme (colours, font, radius, spacing, light/dark) is set from the design system tokens (D16). Plain CSS modules only for small layout tweaks.
**Alternatives:** Tailwind + shadcn/ui (more files to own and style per screen); plain CSS (most effort, most inconsistency). Rejected.

### D8. Catalog list state in the URL
URL keys: `search`, `category` (UUID), `minPrice`, `maxPrice`, `sort` (`title,asc` | `title,desc` | `basePrice,asc` | `basePrice,desc`), `page` (**1-based** for people; sent to the API as `page - 1`). One parse function turns the query string into a validated state with defaults for invalid values, and one serialize function omits defaults, so URLs stay short. The search input updates the URL on submit (Enter or button), not on every keystroke. The query key is the parsed state, so back/forward reuses cached pages.

### D9. Money and display formats
`basePrice` arrives as a JSON number. It is formatted with `Intl.NumberFormat('en-US', { style: 'currency', currency: 'UAH' })` (for example `UAH 1,299.00`). No arithmetic on prices happens in this change; later totals come from Order Service, never from the client.

### D10. Mock backend with MSW, shared by all test levels
`src/mocks/` holds MSW handlers with an in-memory database (users, categories, products) that follow the specs: the same paths without the `/api` prefix behind it, status codes, problem details and edge cases (inactive product → `404`, duplicate email → `409`, `minPrice > maxPrice` → `400`, a role check on `Required` routes). Mock tokens are unsigned JWT-shaped strings with the same claims.
- Used by Vitest component tests (node), by `npm run dev:mock` (MSW service worker in the browser, enabled when `VITE_API_MOCK=true`), and by Playwright's mock project.
- The in-memory database starts from the shared demo fixtures (D14): one account per role and about 45 products across 4 categories, so paging and filters can be shown. Accounts are documented in `README.md`.
- **Risk:** the mock drifting from the real services. Mitigation: the Playwright live project runs the same journeys against the Compose stack, and each handler file names the spec section it follows.

### D11. Test levels and the `check` command
- **Unit/component (Vitest + Testing Library + MSW node):** pure functions (URL state, token decoding, error mapping) and every page's key scenarios from the specs.
- **Browser (Playwright):** project `mock` (starts `dev:mock`, no Docker; runs in `check:e2e`) and project `live` (`E2E_LIVE=1`, expects the gateway on `:8080` and a stack filled by `npm run seed`, D14).
- `npm run check` = `tsc --noEmit` + ESLint + Vitest. `npm run check:e2e` = Playwright mock project. Agents run both before reporting a task done.
- Scenario names from the specs appear in test names, so a failing test points at a requirement.

### D12. Project layout
```
frontend/
  src/
    api/          http.ts, errors.ts, auth.ts, catalog.ts …
    session/      SessionProvider, token decoding, RequireRole
    app/          router, AppShell, navigation, not-found, access-denied
    features/
      auth/       LoginPage, RegisterPage
      catalog/    ProductListPage, ProductDetailsPage, url-state.ts
    components/   shared UI (Price, StockBadge, ProductImage, ErrorState, Loading)
    fixtures/     demo-data.ts shared by the mock and the seed script (D14)
    mocks/        MSW handlers, in-memory db
  scripts/        seed.ts (D14)
  e2e/            Playwright tests (mock + live projects)
  docs/backlog/   backend backlog (exists)
  docs/design/    design system and screen mockups (exist)
```
Each later iteration adds one folder under `features/` and its routes.

### D13. Agent working agreement in the frontend
`frontend/AGENTS.md` (imported by `frontend/CLAUDE.md` and `frontend/GEMINI.md`) already exists (written 2026-10-20, outside this change). It overrides the root mentor rules for `frontend/`, and describes the OpenSpec workflow, the backend backlog workflow, the UI design workflow and the iteration plan. This change only completes its *Checks* section with the real commands once they exist. `.claude/launch.json` gets `frontend-dev` (port 5173, live backend) and `frontend-mock` (port 5174, `dev:mock`) for the preview pane.

### D14. Demo seed script
`npm run seed` (`scripts/seed.ts`, run with `tsx`) fills a running stack through the gateway (`SEED_API_URL`, default `http://localhost:8080`), using the real flows so every event and outbox path runs as in a demo:
1. Log in as the seeded admin (`SEED_ADMIN_EMAIL`, default `admin@voltstore.com`; `SEED_ADMIN_PASSWORD` required, same value as the stack's `ADMIN_PASSWORD`). Create one catalog manager, one inventory worker and one demo customer via `POST /user/non-customer` and `POST /user/register`; `409` means "already there".
2. As the manager, ensure the 4 categories (find by `search`, create if missing) and the ~45 products (find by exact title in the manager's list, create if missing).
3. As the worker, set packaging (`PUT …/packaging`) and adjust stock to each fixture's target quantity (`delta = target − current` from `GET /inventory/stock`; skip when equal). Missing stock items are created by Inventory itself (EP-INV-02 fallback), so the script does not wait for `ProductCreated`.
4. As the manager, activate the products marked active (`PATCH …/status`); retry `503` a few times with back-off. A few fixtures stay inactive, and a few active ones keep stock `0` (shown "Out of stock").
5. Poll the storefront list until the expected products show the expected `stockStatus` (Catalog updates it from stock events), at most 60 s, then print a summary of accounts, counts and anything that did not converge.

Rules: safe to run repeatedly (a second run changes nothing and reports "unchanged"); exits non-zero with a clear message on the first unexpected answer. The fixtures (`src/fixtures/demo-data.ts`: accounts, categories, products with price, description, packaging, target stock, active flag) are **shared with the mock backend** (D10), so mock and live show the same catalog. Seeded passwords are fixed demo values for local stacks only, documented in `README.md`.
**Alternative:** SQL seed files per service database. Rejected: they skip the events and outbox (stock status, stock items) and would have to be kept in sync with five schemas.

### D15. Product image placeholders
Products have no images (backend item BL-06). `ProductImage` renders a placeholder per category: a fixed palette of tinted backgrounds with a line icon per known category (phones, laptops, audio, accessories…), picked by category name with a stable hash fallback for unknown names, and a neutral default when the category is unknown. When BL-06 is done, the component shows `imageUrl` when present and keeps the placeholder as fallback.

### D16. UI design source
Screens follow two design artifacts made with Claude's Design tooling: the **VoltStore design system** (colour, type, spacing, radius, component styles; light and dark) and the **screen mockups** (desktop and phone) for the storefront list, product page, login, cart, checkout/payment and one staff table. Agreed defaults: clean electronics-store look, neutral greys, electric-blue primary, a small "volt" yellow highlight, Inter, light and dark themes. Implementation maps the design system's tokens to the Mantine theme (task 1.6) and builds screens from Mantine components to match the mockups (layout, hierarchy, spacing, colour), not pixel-for-pixel. The files live in `frontend/docs/design/` (listed under *Design artifacts* below); the theme reads `docs/design/design-system/tokens.json` directly, so tokens are never copied by hand.

## Design artifacts

Kept in the repository under `frontend/docs/design/` (source of truth, see its README) and published for viewing as private claude.ai artifacts (D16):
- **Design system:** `docs/design/design-system/README.md` (usage rules, status labels, placeholder rules, Mantine mapping) and `docs/design/design-system/tokens.json` (light and dark colours, type, spacing, radius, shadow, layout). Viewer: https://claude.ai/artifact/6kmKJvD4C3quCvEhLBT7E2.
- **Screen mockups:** `docs/design/screens/`: `Main.dc.html` (storefront list, desktop), `StorefrontPhone.dc.html` (storefront, phone, dark theme), `Product.dc.html`, `Login.dc.html` (with the expired-session notice), `Cart.dc.html` (iteration 2), `Checkout.dc.html` (iteration 3, payment with test cards), `WarehouseStock.dc.html` (iteration 5). Viewer: https://claude.ai/artifact/3x2456ehYVhBGmFFgEWYvf. Mockups show the target state, including features that depend on backlog items (e.g. product titles in the warehouse table need BL-02).

This change implements the header, login, registration (no mockup: same card layout as login), storefront list, phone layout, product page and placeholders. Later iterations use the remaining screens and add mockups for theirs when needed.

## Iteration plan

Each row is a separate OpenSpec change, proposed when the previous one is archived. The durable copy of this plan is in `frontend/AGENTS.md` §6. Backend dependencies are items in `frontend/docs/backlog/`; a `Blocker` item must be `Done` before its iteration is proposed, the others are worked around and marked in code with their ID.

| # | Change | Scope | Agreed notes (2026-10-20) | Backlog |
|---|---|---|---|---|
| 1 | `frontend-foundation` | this change | — | BL-06 (placeholder) |
| 2 | `customer-cart` | guest cart in `localStorage`; server cart (`/customers/cart*`); add from list/details; cart page with live product data per line; limits (10 products, quantity 1–10, `422` violations); replace/keep/merge choice after login | A line whose product answers `404` (inactive) is shown "Unavailable", excluded from the subtotal and removable (US-CUST-04) | — |
| 3 | `customer-checkout-orders` | profile; checkout: `POST /orders/validate`, delivery details pre-filled from the profile, `POST /orders`, pay `POST /orders/{id}/pay`, `402/409/503` handling; order history and details with TTN and delivery status; cancel (`202`) | Submit and Pay buttons are disabled while their request runs. The pay page shows `reservationExpiresAt` as information only and relies on the backend's answer (`409 payment-window-expired` → show expired, reload the order); no status logic in the client. The pay form lists the mock test cards (`…4242` approved, `…0002` insufficient funds, `…5000` lost answer then approved on retry, others declined). Carrier select offers `NOVA_POSHTA` only. Order details and history poll every 5 s while an order is in a non-final state | BL-07, BL-11, BL-13 (workarounds) |
| 4 | `catalog-manager-console` | product table with status filter, create/edit, activate/deactivate (`422` readiness reasons, `503`), categories CRUD (`409` on delete with products) | Edit form loads the product by id with the manager's token | **BL-01 (blocker)** |
| 5 | `inventory-worker-console` | stock table (`packagingConfigured` filter, sort), restock/write-off, packaging form, fulfillment queue with delivery status and failure reason, ship (`409` until TTN), TTN retry (`424 { reason }`) | Fulfillment queue polls every 5 s | **BL-02 (blocker)**, BL-03, BL-13 (workaround) |
| 6 | `admin-staff-accounts` | create staff/admin accounts (`POST /user/non-customer`) | Create only; no user list | BL-09 (later) |
| 7 | `frontend-deployment` | nginx image, Compose service, Kubernetes Deployment/Service, host-specific Ingress rule (D2), NetworkPolicy allowing the ingress controller to reach the frontend pod | — | — |

## Risks / Trade-offs

- [Mock and real backend drift apart] → handlers cite spec sections; the Playwright `live` project runs the same journeys against Compose; any mismatch found is fixed in the mock and the API types together.
- [Hand-written API types go stale when a service spec changes] → each `src/api/<service>.ts` names its spec; `frontend/AGENTS.md` tells agents to re-check them when a service spec changes.
- [Token in `localStorage` is exposed to XSS] → no third-party scripts, React escaping, 1-hour lifetime; accepted for a demo (D5).
- [No refresh token: users are logged out after 1 hour, possibly mid-form] → expiry notice and return to the same page after login; forms of later iterations keep unsent input where cheap.
- [Storefront hides the manager's view] → intentional (spec `catalog-browsing`); managers get their own product table in iteration 4.
- [Mantine and the other libraries add bundle weight] → irrelevant for a demo; no action.
- [Seed script meets eventual consistency (stock status arrives via Kafka) or a slow first start] → it creates stock items itself through EP-INV-02, retries `503` on activation, and polls with a 60 s limit, reporting what did not converge instead of failing silently.
- [Seed fixtures and the real catalog drift apart after manual edits in the live stack] → the script only creates what is missing and adjusts stock to target; it never deletes, so manual additions survive and a rerun restores the demo data.
- [Mockups promise more than Mantine gives cheaply] → mockups are a reference; match layout, hierarchy and tokens, and prefer a stock component over a custom one.

## Migration Plan

Greenfield; nothing to migrate. Rollback is deleting `frontend/` build output; no other system depends on it until iteration 7.

## Open Questions

- Exact library versions: take the current stable releases when the project is scaffolded and pin them.
- Whether `basePrice` is serialized as a JSON number or string by Catalog: the API layer accepts both and converts to a number; confirm against the running service in the live browser test.
