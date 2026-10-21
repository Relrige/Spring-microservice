# AGENTS.md — VoltStore Frontend

Operating manual for AI agents (Claude, Gemini, Codex, …) working in `frontend/`. The root [`../AGENTS.md`](../AGENTS.md) describes the project as a whole; how agents work inside `frontend/` is defined here.

---

## 1. What this project is

- The web frontend of VoltStore: one single-page app (React, TypeScript, Vite) for all four roles: customer, catalog manager, inventory worker, admin.
- Its only job is to **demonstrate the microservices** end to end. Frontend development is **not** a learning goal of the team.
- The backend is reached only through the API Gateway, under the same origin: the browser calls `/api/<gateway path>` and a proxy strips `/api` (Vite dev proxy now; Ingress rule later). No backend or gateway change is needed for that.

## 2. Fully agentic development

Development in `frontend/` is fully agentic. The root working modes (mentor, autopilot) do not apply here, so there is no mode to choose.

- Implement directly; explain decisions briefly, do not teach.
- Agents write all code, tests, configuration and docs here.
- Run installs, builds, tests and dev servers as needed; say what you ran in the summary.
- Agents commit their own work without being asked, following §8. Pushing, opening pull requests and merging still wait for a person.
- Everything is written in English, and docs never assign work to individual team members.

Hard boundary: **do not change anything outside `frontend/`** (services, gateway, `../docs/`, `../k8s/`, Compose) from frontend work. When the frontend needs something the backend does not provide, add a backlog item (§4) and build a clearly marked workaround or placeholder.

## 3. Workflow: OpenSpec changes

Frontend work is planned and delivered as OpenSpec changes in [`openspec/`](openspec/), **one change per iteration** (see §6). Use the OpenSpec skills or slash commands:

1. `/opsx:propose` — create a change (proposal, specs, design, tasks).
2. `/opsx:apply` — implement its tasks; tick each task only after its stated verification passed.
3. `/opsx:archive` — after review, merge the change's specs into [`openspec/specs/`](openspec/specs/), the current behaviour of the frontend.

Rules:
- Requirements come from the backend contracts: [`../docs/design/services-specs/`](../docs/design/services-specs/) and [`../docs/design/services-requirements/api-contracts.md`](../docs/design/services-requirements/api-contracts.md). When the frontend and a service spec disagree, the service spec wins; if the spec looks wrong, that is a backlog item, not a frontend decision.
- Read a change's `design.md` before implementing it; decisions there are binding unless a person changes them.
- API types in `src/api/<service>.ts` are hand-written from the service specs and each cites its spec section. When a service spec changes, re-check the matching file and the mock handlers. They are replaced by generated types once BL-04 is done.
- A `Blocker` backlog item must be `Done` before the iteration it blocks is proposed.

## 4. Workflow: backend backlog

[`docs/backlog/`](docs/backlog/) lists what the **backend** should change so the frontend can work properly. The frontend owns the list; the backend team decides on and implements the items in their own task flow (`../docs/tasks/`).

- [`docs/backlog/README.md`](docs/backlog/README.md) is the index: one row per item with ID, name, status, importance, services, the frontend iteration it blocks, and a one-line summary.
- Each item has its own file `docs/backlog/BL-NN-short-name.md`, written from [`docs/backlog/_template.md`](docs/backlog/_template.md).

**Adding an item:** take the next free `BL-NN`, copy the template, fill every section (cite the spec sections and code you checked), and add the index row in the same edit. One item = one problem; an item may touch several services.

**Status values:** `Open` (not started) → `In progress` → `Done` (implemented in the backend and verified from the frontend), or `Rejected` (with the reason in the item file). Keep the index row and the item's frontmatter equal.

**Importance values:**
- `Blocker` — a planned frontend iteration cannot be delivered properly without it.
- `Important` — the frontend works without it, but with a visible gap or a fragile workaround.
- `Nice to have` — improves the demo or the engineering quality; no frontend need.
- `Later` — agreed to be postponed; do not plan around it.

**Workarounds in code** for an open item carry a comment naming it, e.g. `// BL-02: titles unavailable for inactive products until Catalog lets workers see them`, so they can be found and removed when the item is `Done`.

**Discovered during work:** a backend gap can come up while implementing a change, not just while proposing one. Examples: a missing endpoint or field, a service that doesn't behave as its spec says, or a spec that is wrong or incomplete. When that happens:

1. Add the backlog item right away (next free `BL-NN`, template, index row), in the same commit as the workaround that needs it. If there is no workaround yet, use its own commit, `Frontend: backlog BL-NN <short name>`.
2. Name the item in the commit body, and in the summary at the end of `/opsx:apply`, so a person sees it.
3. If the item is a `Blocker` for the iteration in progress, stop working on the affected tasks and report it. Don't build a workaround or tick those tasks until a person decides. A gap that only affects a later iteration doesn't stop the current one.
4. Never "fix" the gap by changing a service, the gateway or `../docs/` (§2). If the change's `design.md` or tasks no longer match, suggest updating the change rather than quietly working around it.

## 5. Workflow: UI design

The design lives in [`docs/design/`](docs/design/), which is the source of truth: the design system ([`docs/design/design-system/README.md`](docs/design/design-system/README.md) and `tokens.json`) and one mockup per screen in [`docs/design/screens/`](docs/design/screens/). Read [`docs/design/README.md`](docs/design/README.md) for how to use them. The same files are published as private claude.ai artifacts for viewing (links in that README); agents work from the repository copies.

Mockups are a visual reference, not code: build screens with Mantine and the theme generated from `tokens.json`, matching layout, hierarchy, colours and spacing. A screen without a mockup follows the design system README and the nearest existing screen. Design changes are made in `docs/design/` (and republished to the artifacts when you can).

## 6. Iteration plan

Each iteration is one OpenSpec change. Backend dependencies refer to backlog IDs.

| # | Change | Scope | Backlog dependencies |
|---|---|---|---|
| 1 | `frontend-foundation` | tooling, API layer, session, shell, storefront browsing, mock backend, demo seed script | — |
| 2 | `customer-cart` | guest cart, server cart, merge choice on login | — |
| 3 | `customer-checkout-orders` | profile, checkout, payment, order history and details, cancel | BL-07, BL-11, BL-13 (workarounds) |
| 4 | `catalog-manager-console` | products and categories management | BL-01 (blocker) |
| 5 | `inventory-worker-console` | stock, packaging, fulfillment queue, ship, TTN retry | BL-02 (blocker), BL-03, BL-13 (workaround) |
| 6 | `admin-staff-accounts` | create staff and admin accounts | — |
| 7 | `frontend-deployment` | nginx image, Compose, Kubernetes, Ingress | — |

## 7. Checks

Before reporting any task as done (all commands run in `frontend/`):

- `npm run check` — `tsc -b`, ESLint and every Vitest test (unit and component tests run against the MSW mock handlers). About 20–30 s. Must pass before every commit (§8).
- `npm run check:e2e` — Playwright journeys (project `mock`) against `npm run dev:mock` on port 5174, which Playwright starts itself; no Docker. About 20 s. Run it when a change touches pages, routing or the session. First time on a machine: `npx playwright install chromium`.
- `npm run test:e2e:live` — the same journeys against the Compose stack (`npm run dev` on 5173 → gateway on 8080), when a task says so: start the stack, run `npm run seed` with `SEED_ADMIN_PASSWORD`, then the tests with `E2E_ADMIN_PASSWORD`.
- To look at the app yourself: `npm run dev:mock` (or the `frontend-mock` entry of `.claude/launch.json` in the preview pane) and the demo accounts in `README.md`.

Test conventions:
- Test names contain the spec scenario names, so a failure points at a requirement.
- Component and page tests render through `renderApp(url)` / `renderWithProviders()` (`src/test/render.tsx`) and use the mock handlers (`src/mocks/`); override single answers with `server.use(...)`. The mock database is reset after each test.
- A new backend call gets its handler in `src/mocks/handlers.ts`, following the service spec (status codes, problem details), in the same commit.

The exact commands, ports, demo accounts and seed variables are in [`README.md`](README.md).

## 8. Commits

Agents commit their own work in `frontend/` as they go; nobody needs to ask for it.

- **Branch.** Commit on the current frontend branch (today `frontend`), never directly on `main`. Do not push, force-push, rewrite pushed history, open pull requests or merge unless a person asks.
- **Scope.** Stage files explicitly by path under `frontend/` (never `git add -A` or `git add .` from the repository root). Never commit secrets, `.env` files, `node_modules/`, build output or test reports.
- **Granularity.** One commit per logical unit: usually one OpenSpec task, or a few tightly related ones. Tests, docs and the ticked checkbox in `tasks.md` go in the same commit as the code they belong to. Do not mix unrelated changes; split a mixed working tree into several commits.
- **Green before commit.** Once the project has checks (§7), `npm run check` passes before every commit. Never skip hooks (`--no-verify`).
- **Subject line** (max ~72 characters, no trailing period), following the repository's style:
  - OpenSpec task work: `Frontend <change> <task ids>: <what was done>`, e.g. `Frontend foundation 2.1–2.2: API request layer and session-expired signal`.
  - Everything else: `Frontend: <what changed>`, e.g. `Frontend: backend backlog with 13 items`.
- **Body:** a blank line, then two to six lines on what changed and why: decisions, spec or backlog references (`BL-02`), anything a reviewer should know. Wrap at 72 characters.
- **Trailer:** end with the co-author line of the agent that made the commit (for Claude: `Co-Authored-By: Claude <model> <noreply@anthropic.com>`).

