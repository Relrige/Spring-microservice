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
| `npm run check` | Types, lint and all unit and component tests (Vitest). Run before every commit. |
| `npm run check:e2e` | Browser journeys (Playwright, project `mock`) against `dev:mock`; starts the server itself. |
| `npm run test:e2e:live` | The same journeys (project `live`) against the Compose stack through `npm run dev`. Seed first. |
| `npm run seed` | Fill a running stack with the demo data (below). |
| `npm run format` | Format with Prettier. |
| `npm test`, `npm run test:watch`, `npm run lint`, `npm run typecheck` | The parts of `check` alone. |

First run of the browser tests on a machine: `npx playwright install chromium`.

The preview pane of the Claude desktop app has two launch entries in [`.claude/launch.json`](.claude/launch.json): `frontend-dev` (5173, live backend) and `frontend-mock` (5174, mock backend).

## Demo accounts

The mock backend and `npm run seed` use the same fixtures ([`src/fixtures/demo-data.ts`](src/fixtures/demo-data.ts)): 4 categories and 45 products (42 active, of which 5 are out of stock; 3 inactive), and these accounts. The passwords are fixed demo values for local stacks only.

| Role | Email | Password |
|---|---|---|
| Customer | `customer@voltstore.com` | `customer-demo` |
| Catalog manager | `manager@voltstore.com` | `manager-demo` |
| Inventory worker | `worker@voltstore.com` | `worker-demo` |
| Admin | `admin@voltstore.com` | mock: `admin-demo`; live: the stack's admin password (below) |

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

Not yet run against the Compose stack.
