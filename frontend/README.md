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
| `npm run test:e2e:live` | The same journeys (project `live`) against the Compose stack through `npm run dev`, or against a deployment when `E2E_BASE_URL` is set (no dev server then). Seed first. |
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

Every demo product links a freely licensed photo on Wikimedia Commons (credits and licences in [`docs/image-credits.md`](docs/image-credits.md)). The photos load from Commons, so showing them needs internet access; without it every product shows its category placeholder. `npm run seed` sets the photo on products it creates and on fixture products that have another image.

| Role | Email | Password |
|---|---|---|
| Customer | `customer@voltstore.com` | `customer-demo` |
| Catalog manager | `manager@voltstore.com` | `manager-demo` |
| Inventory worker | `worker@voltstore.com` | `worker-demo` |
| Admin | `admin@voltstore.com` | mock: `admin-demo`; live: the stack's admin password (below) |

The demo catalog manager uses the manager console at `/manage`: the product list with every product, active or not (search, category, status, sort), the forms to create and edit a product, activation and deactivation, and the categories page (create, rename, delete). A product created there stays inactive, and in the mock it has no packaging, so its activation is rejected until a worker sets packaging (iteration 5); the fixture products can be activated and deactivated. The inventory worker and the admin still see placeholder pages.

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

## Deployment

The image ([`Dockerfile`](Dockerfile)) builds the app with Vite and serves it with nginx on port 8080 as an unprivileged user. [`deploy/nginx.conf`](deploy/nginx.conf) forwards `/api/*` to `api-gateway:8080` with `/api` stripped (the same as the dev proxy), answers every other path with `index.html` so deep links survive a reload, caches `/assets/` for a year and answers `/healthz` for probes. The image has no build-time API address: `api-gateway` is the gateway's name in both Compose and Kubernetes.

**Docker Compose.** The root [`docker-compose.yml`](../docker-compose.yml) has a `frontend` service on <http://localhost:3000>:

```bash
docker compose up -d --build frontend
E2E_BASE_URL=http://localhost:3000 E2E_ADMIN_PASSWORD=<admin password> npm run test:e2e:live
```

The seed keeps targeting the gateway directly (`SEED_API_URL` default `http://localhost:8080`).

**Kubernetes (minikube).** [`../k8s/frontend/`](../k8s/frontend/) holds the Deployment and the Service `frontend:8080`. The Ingress sends `/` to the frontend, so with `minikube tunnel` running the app is at <http://localhost/> and the API at `http://localhost/api/…` (no longer at the Ingress root; BL-14 tracks the gateway docs). Build and load the image, apply the manifests and run the journeys through the Ingress (commands in [`../k8s.txt`](../k8s.txt)):

```bash
docker build -t voltstore/frontend:latest .
minikube image load voltstore/frontend:latest
kubectl apply -f ../k8s/frontend/
SEED_API_URL=http://localhost/api SEED_ADMIN_PASSWORD=<admin password> npm run seed
E2E_BASE_URL=http://localhost E2E_ADMIN_PASSWORD=<admin password> npm run test:e2e:live
```

The cluster's admin password is `AUTH_ADMIN_PASSWORD` in the Secret `auth-secrets`. `minikube image load` does not refresh images on its own: when journeys fail on a contract detail, compare the cluster's service images with the current builds first.

## Live verification

Last run: 2026-10-23, branch `frontend-product-images` (change `product-images`), Compose stack of the repository root with Catalog (task 30, PR #35) and the frontend image rebuilt from this branch; the gateway published on host port 18080 through a local override, because another program held 8080.

- `npm run seed` (`SEED_API_URL=http://localhost:18080`): first run `images set: 45` (the existing fixture products got their Commons photo through `PUT`), second run `Result: unchanged`. Before the first run Catalog answered `"imageUrl": null` for products without an image.
- `E2E_BASE_URL=http://localhost:3000 npm run test:e2e:live`: 15 of 15 journeys passed (about 46 s). `npm run check:e2e` against the mock: 15 of 15.
- The storefront, the product page and the manager list on port 3000 show the photos; the manager form shows the image field with its preview (checked in the mock).
- No mismatch between the mock and Catalog for `imageUrl` (validation message, `null` when unset, `PUT` clears a missing value).

Previous run: 2026-10-22, branch `frontend-deployment` (change `frontend-deployment`).

- **Compose.** Through the `frontend` container on port 3000: `/api/catalog/products?size=1` returned the gateway's JSON, `/api/no/such/route` the gateway's `404` problem. `E2E_BASE_URL=http://localhost:3000 npm run test:e2e:live`: 11 of 11 journeys passed (about 41 s).
- **Kubernetes.** minikube with `minikube tunnel`; the Ingress served the app (`/healthz` 200, `/account/orders` with `Cache-Control: no-cache`) and the API under `/api`. The first run failed two journeys because the cluster's service images were 21 hours old (Catalog answered without `size`, BL-05); after loading the current Compose builds and restarting the services, `npm run seed` through `http://localhost/api` twice and `E2E_BASE_URL=http://localhost npm run test:e2e:live`: 11 of 11 passed (about 41 s). A reload of `/account/orders` as the demo customer kept the orders page.
- The image was checked on its own first: deep links get `index.html` with `no-cache`, `/assets/` files `max-age=31536000, immutable`, and the container runs as uid 101.

Previous run: 2026-10-22, branch `catalog-manager-console` (change `catalog-manager-console`), Compose stack of the repository root with the catalog, gateway and inventory images rebuilt at `4551bbe` (Catalog tasks 27–29, gateway task 12, Inventory tasks 22–24).

- `npm run seed`: `Result: seeded.` (only the demo cart and profile were set again); storefront 42 active products with the expected stock status.
- BL-01 through the gateway, `GET /api/catalog/products/{id}` for the inactive fixture "Xiaomi 14T, 512 GB": with the manager's token `200` with `description` and `status: NOT_ACTIVE`; without a token `404`; with a malformed token `401`. BL-01 is `Done`.
- `npm run test:e2e:live`: 13 of 13 journeys passed (about 65 s), including the three manager journeys: a category created, renamed and deleted (nothing left behind); the draft product "E2E draft product" with its activation rejected for missing packaging, a new description saved and loaded again after a reload (BL-01 end to end); "TP-Link Archer AX55 Wi-Fi 6 router" deactivated, gone from the storefront search, and activated again. A second run passed too and reused the one draft product. `npm run check:e2e` against the mock: 13 of 13.
- Catalog contract: no mismatch between the mock and Catalog in the journeys. The mock's manager handlers copy the live answers probed through the gateway: problem titles and details, `errors` per field, `404` before body checks on `PUT` and body checks before `404` on `PATCH …/status`.
- The expiry journey no longer waits for the manager page before the session ends: `/manage` now lands on the product list, which reads with the token, and the journey's rewritten (unsigned) token is answered `401` by the gateway at once.
- Stack setup on that machine (local, gitignored files and volumes only): the stopped containers of another Compose project (a worktree) held the fixed container names and were removed (their volumes kept). Order and Payment did not start because their database volumes held an older schema; both volumes were deleted and recreated. Keys missing from the local `.env` of `payment-service` (`INTERNAL_TOKEN`, equal to Auth's `AUTH_INTERNAL_TOKEN`, and `PAYMENT_GATEWAY_PROVIDER`) and of `order-service` (the service URLs, Kafka and topic names) were added from their examples.

Previous run: 2026-10-22, branch `admin-staff-accounts` (change `admin-staff-accounts`), the same Compose stack as the previous run (no service changed).

- `npm run test:e2e:live`: 11 of 11 journeys passed (about 55 s), including the admin journey: the admin created an inventory worker through `POST /user/non-customer`, and the new worker logged in and landed on the stock page. Each run leaves one `e2e-worker-…@example.com` account (accounts cannot be removed, BL-09). `npm run check:e2e` against the mock: 11 of 11.
- No mismatch between the mock and Auth Service.

Previous run: 2026-10-22, branch `inventory-worker-console` (change `inventory-worker-console`), Compose stack of the repository root with the inventory, delivery and gateway images rebuilt from main (Inventory tasks 22–24, gateway task 12).

- Inventory applied migration V5 (product data copy); its backfill filled the title and status of all 45 stock rows about a minute after startup (BL-02).
- `npm run seed`: `Result: seeded.` (stock adjusted back to the fixture targets for 2 products after earlier journeys).
- `npm run test:e2e:live`: 10 of 10 journeys passed (about 50 s), including the worker journey: a paid order reached the fulfillment queue, got its TTN from Delivery and was marked as shipped; the stock page searched by title, restocked and wrote off one unit and saved packaging. `npm run check:e2e` against the mock: 10 of 10.
- Found in the run: a journey's payment clears the ordered cart lines asynchronously (`OrderPaid`), sometimes after the next journey's demo-cart reset. The reset in `e2e/support.ts` now checks the cart for a few seconds and puts it back if it changed. No mismatch between the mock and the services.

Previous run: 2026-10-22, branch `new-feature` (change `customer-checkout-orders`), Compose stack of the repository root with the catalog and order images rebuilt after Catalog tasks 27–29 and Order tasks 19–20.

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
