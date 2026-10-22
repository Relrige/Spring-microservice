# VoltStore Frontend

The web app of VoltStore: one React + TypeScript single-page app for customers, catalog managers, inventory workers and admins. It exists to demonstrate the microservices and is built by coding agents; read [`AGENTS.md`](AGENTS.md) before changing anything.

- Stack: React 19, TypeScript 6 (strict), Vite 8, Mantine 9, TanStack Query 5, React Router 8, MSW 3, Vitest 5, Playwright.
- The browser calls `/api/<gateway path>` on its own origin; a proxy strips `/api` and forwards to the API Gateway (design D2). No CORS, no backend change.
- Plans and specs: [`openspec/`](openspec/). Backend gaps: [`docs/backlog/`](docs/backlog/). UI design: [`docs/design/`](docs/design/).

## Requirements

- Node.js 22.22 or newer (npm 10+).
- For the live backend: Docker with the Compose stack of the repository root (`docker compose up -d --build` in `..`), gateway on `http://localhost:8080`.

## Commands

| Command | What it does |
|---|---|
| `npm install` | Install the exact versions from `package-lock.json`. |
| `npm run dev` | Dev server on <http://localhost:5173>; `/api` is proxied to `VITE_API_TARGET` (default `http://localhost:8080`). Needs the stack. |
| `npm run dev:mock` | Dev server on <http://localhost:5174> with the mock backend (MSW in the browser). No Docker needed. |
| `npm run build` | Type-check and build to `dist/`. |
| `npm run preview` | Serve the build on <http://localhost:4173> (no API proxy). |
| `npm run check` | `check:api`, types, lint and all unit and component tests (Vitest). Run before every commit. |
| `npm run gen:api` | Regenerate the API types in `src/api/generated/` from the services' OpenAPI documents (below). |
| `npm run check:api` | Fail if the generated API types differ from what the current OpenAPI documents produce. |
| `npm run check:e2e` | Browser journeys (Playwright, project `mock`) against `dev:mock`; starts the server itself. |
| `npm run test:e2e:live` | The same journeys (project `live`) against the Compose stack through `npm run dev`. Seed first. |
| `npm run seed` | Fill a running stack with the demo data (below). |
| `npm run format` | Format with Prettier. |
| `npm test`, `npm run test:watch`, `npm run lint`, `npm run typecheck` | The parts of `check` alone. |

First run of the browser tests on a machine: `npx playwright install chromium`.

The preview pane of the Claude desktop app has two launch entries in [`.claude/launch.json`](.claude/launch.json): `frontend-dev` (5173, live backend) and `frontend-mock` (5174, mock backend).

## API types

The request and response types come from the OpenAPI documents the services commit next to their code: `../<service>-service/openapi/public.json` for Auth, Catalog, Customer, Order, Inventory and Delivery (rules in [`../docs/design/services-requirements/openapi-conventions.md`](../docs/design/services-requirements/openapi-conventions.md)). `npm run gen:api` turns each into `src/api/generated/<service>.ts` with `openapi-typescript`. The generated files are committed, so the build does not need the backend folders, and are never edited by hand. `src/api/<service>.ts` aliases them and makes the calls.

When a service changes its document, `npm run check` fails at `check:api`. Run `npm run gen:api`, review the diff of the generated files (it is the API change), and adapt the code and the mock handlers.


## Demo accounts

The mock backend and `npm run seed` use the same fixtures ([`src/fixtures/demo-data.ts`](src/fixtures/demo-data.ts)): 4 categories and 45 products (42 active, of which 5 are out of stock; 3 inactive), and these accounts. The passwords are fixed demo values for local stacks only.

| Role | Email | Password |
|---|---|---|
| Customer | `customer@voltstore.com` | `customer-demo` |
| Catalog manager | `manager@voltstore.com` | `manager-demo` |
| Inventory worker | `worker@voltstore.com` | `worker-demo` |
| Admin | `admin@voltstore.com` | mock: `admin-demo`; live: the stack's admin password (below) |

The demo customer also has a demo cart (`DEMO_CART`): 1 × Sony WH-1000XM5 headphones, 2 × Anker 737 power bank and 1 × the inactive Xiaomi 14T, which the cart page shows as unavailable. A visitor who adds products and then logs in as the demo customer meets the replace/keep/merge choice at once. The mock starts every page load with this cart; `npm run seed` sets it only while the demo customer's cart is empty, and the live journeys put it back before each cart journey.

The demo customer also has a demo profile (`DEMO_PROFILE`): Olena Kovalenko, `+380501234567`, "Kyiv, branch No. 12, Velyka Vasylkivska St 72", which pre-fills the checkout's delivery form. The mock starts with it; `npm run seed` sets it only while the profile was never saved. On the payment step the demo provider's test cards apply: `…4242` approves, `…0002` declines (insufficient funds), `…5000` approves after a lost answer, any other number declines.

The mock keeps its data in memory: a reload of the page starts again from the fixtures (registered users are lost; sessions survive because mock tokens are self-contained).

## Seeding a live stack

```bash
SEED_ADMIN_PASSWORD=<admin password> npm run seed
```

| Variable | Default | Meaning |
|---|---|---|
| `SEED_ADMIN_PASSWORD` | — (required) | Password of the stack's initial admin: `AUTH_ADMIN_PASSWORD` in `../auth-service/.env`. |
| `SEED_ADMIN_EMAIL` | `admin@voltstore.com` | `AUTH_ADMIN_EMAIL` in the same file. |
| `SEED_API_URL` | `http://localhost:8080` | The API Gateway. |

The script works only through the gateway, as the real roles: the admin creates the manager and worker (`POST /user/non-customer`), the customer registers; the manager creates missing categories and products; the worker sets packaging and adjusts stock to each fixture's target; the manager activates the active ones (retrying `503`). It then polls the storefront until every active product shows the expected stock status (Catalog learns it from Inventory events), for at most 60 s, and prints a summary. It never deletes anything and only creates what is missing, so a second run reports `Result: unchanged`. Exit codes: `0` done, `1` failed (with the step and the answer), `2` seeded but the storefront did not converge in time.

On PowerShell: `$env:SEED_ADMIN_PASSWORD='<admin password>'; npm run seed`.

## Live browser tests

1. Start the stack in the repository root and wait until the gateway answers: `docker compose up -d --build`.
2. Seed it: `SEED_ADMIN_PASSWORD=<admin password> npm run seed`.
3. Run `E2E_ADMIN_PASSWORD=<admin password> npm run test:e2e:live` (the admin password is needed by the staff-login journey).

Result of the last live run: see *Live verification* below.

## Live verification

Last run: 2026-10-22, branch `new-feature` (change `customer-checkout-orders`), Compose stack of the repository root with the catalog and order images rebuilt after Catalog tasks 27–29 and Order tasks 19–20.

- `npm run seed`, first run: `Result: seeded.` with `demo profile: set`; second run: `Result: unchanged`, `demo cart: kept, demo profile: kept`. Storefront: 42 active products with the expected stock status.
- `npm run test:e2e:live`: 9 of 9 journeys passed (about 30 s), including checkout with a declined (`…0002`) and an approved (`…4242`) card and cancelling an unpaid order. `npm run check:e2e` against the mock: 9 of 9.
- BL-07 through the gateway: `POST /orders` twice with one `Idempotency-Key` answered `201` with the same order, the second with `Idempotent-Replayed: true`; the key with another body answered `422 idempotency-key-mismatch`. The test order was cancelled afterwards.
- Order contract: no mismatch between the mock and Order Service in the journeys (status codes, problem types, the `202` cancel answer).
- Stack setup on that machine: Order Service did not start because its database volume held an older schema (Flyway V1: `column "placed_at" does not exist`); the volume `spring-microservice_order-service-postgres` was deleted and recreated.

Previous run: 2026-10-22, branch `customer-cart` (change `customer-cart`), Compose stack of the repository root rebuilt from the current sources.

- `npm run seed`, first run: `Result: seeded.` with `demo cart: set` (3 lines); second run: `Result: unchanged`, `demo cart: kept`. Storefront: 42 active products with the expected stock status.
- `npm run test:e2e:live`: 7 of 7 journeys passed (about 25 s), including the guest cart with reload and the login as the demo customer with "Merge both", whose cart page shows the demo cart's two available lines and its unavailable line. `npm run check:e2e` against the mock: 7 of 7.
- Cart contract: no mismatch between the mock and Customer Service (status codes, `422` cart-rule problems, cart order by `productId`).
- The live database holds categories left by earlier backend tests (e.g. `Phones 1791620167199`), so the browse journey now selects the category radio by exact name.
- Stack setup needed on that machine (local, gitignored files and volumes only): `.env` of `api-gateway`, `customer-service` and `notification-service` created from their examples; missing keys added to the `.env` of `inventory-service` and `delivery-service` from their examples. The inventory database volume held an older V1 schema (Flyway V2 failed), so it was deleted and recreated. A parallel `docker compose up --build` failed with a BuildKit `lease not found` error and left the catalog, auth, order and delivery images stale (Catalog answered without `size`, tokens without `email`); building those images one by one fixed it. `order-service` still did not start (its database volume holds an older schema); this iteration does not use it.

Previous run: 2026-10-22, branch `frontend/openapi` (change `api-contract-types`), Compose stack of the repository root rebuilt after the springdoc merge; only the gateway, Auth, Catalog, Inventory and Customer with their databases and Kafka were started.

- Catalog lists answer the paging envelope with `size` (`GET /catalog/products?size=1` → `"page":0,"size":1`), and `basePrice` is a JSON number, as the generated types say.
- `npm run seed` through the modules rebuilt on the generated types: `Result: unchanged`, storefront 42 active products with the expected stock status.
- `npm run test:e2e:live`: 5 of 5 journeys passed (about 22 s). `npm run check:e2e` against the mock: 5 of 5.

Previous run: 2026-10-20, branch `frontend`, Compose stack of the repository root (Docker Desktop, 8 GB).

- Dev proxy: `/api/catalog/categories` on port 5173 returned the gateway's answer unchanged; an unknown `/api/...` path got the gateway's `404` with the `/api` prefix stripped.
- `npm run seed`, first run: 3 accounts, 4 categories and 45 products created, packaging set for 45, stock adjusted for 38, 42 activated; the storefront showed the expected stock statuses after about 8 s. Second run: `Result: unchanged`, with the same product count and stock list before and after.
- `npm run test:e2e:live`: 5 of 5 journeys passed (about 20 s). `npm run check:e2e` against the mock: 5 of 5.
- Contract findings: Catalog serializes `basePrice` as a JSON number (`79999.00`). Catalog orders titles by code point (`ASUS…` before `Acer…`), and the mock now does the same.
- Stack setup needed on that machine (local, gitignored files only): `customer-service/.env` copied from its `.env.example`; keys that were missing from the local `.env` of `api-gateway`, `catalog-service` and `inventory-service` added from their examples; `GATEWAY_UPSTREAMS_AUTH` corrected to `http://auth-service:8080`. `order-service` and `payment-service` did not start because their database volumes hold an older schema; this iteration does not use them.

The live data may contain products besides the fixtures (earlier tests), so the live journeys check the product total as "at least the fixtures".
