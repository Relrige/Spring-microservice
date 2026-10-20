# Proposal

## Why

VoltStore's backend (gateway plus eight services) can only be demonstrated through API test collections today; there is no user interface for the four roles the services were designed for. A web frontend is needed to demonstrate the system end to end. It is not a study goal: it is built and maintained entirely by coding agents, so this first iteration must also give agents a project they can change safely on their own (fast checks, a mock backend, browser tests).

This change lays the foundation and delivers the first slice a visitor can use: sign-in and registration, and browsing the public catalog. Later iterations add the role-specific areas one at a time (see design.md, *Iteration plan*).

## What Changes

- New single-page web application in `frontend/` (React, TypeScript, Vite), reaching the backend only through the API Gateway under the same origin (`/api/...` → gateway, prefix stripped by a proxy). No backend or gateway change.
- Shared API access layer: one place that adds the session token, reads the services' RFC 9457 problem details, and turns `400`/`401`/`403`/`404`/`409`/`422`/`5xx` and network failures into consistent user-facing behaviour.
- User session: customer registration, login for every role, logout, session restored on reload, expiry handled (tokens live 1 hour, no refresh).
- Application shell: header, role-aware navigation, route guards per role, landing area per role, not-found and access-denied pages.
- Public catalog browsing: product list with search, category, price range, sorting and paging kept in the URL; product details page; stock status shown from Catalog's read model; a placeholder image per category, because products have no images (backend item BL-06).
- Agent-ready project setup: strict TypeScript, lint and format, unit/component tests, a mock backend that follows the service specs so the UI runs without the stack, browser tests against the mock and against the real Compose stack, a single `check` command, a preview launch configuration, and the commands and checks section of `frontend/AGENTS.md`.
- Demo seed script: fills a running stack through the gateway with accounts for every role, categories and active, stocked products, using the same fixtures as the mock backend, so the live app and the live browser tests have something to show.
- Screens follow the VoltStore UI design artifacts (design system and screen mockups).

Already in place outside this change (2026-10-20): `frontend/AGENTS.md` with the agentic working agreement, and the backend backlog in `frontend/docs/backlog/` listing what the backend should change for later iterations.

Not in this change (later iterations): the cart, checkout, payment, orders and profile for customers; the catalog manager console; the inventory worker console; the admin staff-account page; container image and Kubernetes deployment.

## Capabilities

### New Capabilities
- `api-access`: how the browser reaches the backend (same-origin `/api` prefix) and how responses, problem details and failures are presented to the user.
- `user-session`: registration, login, logout, session persistence and expiry, and the identity (user id, role, email) the UI derives from the token.
- `app-navigation`: application shell, role-aware navigation, route protection per role, landing areas, not-found and access-denied pages.
- `catalog-browsing`: the storefront product list with filters, sorting and paging, and the product details page, as any visitor or customer sees them.

### Modified Capabilities
<!-- none: no frontend specs exist yet -->

## Impact

- New code only, under `frontend/`; the Spring services, the gateway and their specs are unchanged.
- The application uses gateway routes `POST /user/register`, `POST /auth/login`, `GET /catalog/products`, `GET /catalog/products/{id}`, `GET /catalog/categories`.
- The seed script also uses `POST /user/non-customer` (admin), `POST /catalog/categories`, `POST /catalog/products`, `PATCH /catalog/products/{id}/status` (manager), and `GET /inventory/stock`, `POST /inventory/stock/{id}/adjust`, `PUT /inventory/stock/{id}/packaging` (worker). It needs the seeded admin's password from the stack's configuration.
- Development needs Node.js (≥ 20) and, for live browser tests, the Docker Compose stack with the gateway on `localhost:8080`.
- New dev dependencies (Vite, React, TanStack Query, React Router, Vitest, Testing Library, MSW, Playwright, ESLint, Prettier); nothing is added to the Java builds.
