# Spec Delta

## Purpose

How the built frontend is served outside the dev server: the app and the API on one origin, deep links that survive a reload, sensible caching, and the same behaviour in Docker Compose and in Kubernetes behind the Ingress.

## ADDED Requirements

### Requirement: One origin for app and API
The deployed app SHALL be served at `/` and SHALL forward every request under `/api/` to the API Gateway with the `/api` prefix removed and the rest of the path and the query unchanged. The gateway's answers, including problem details and status codes, SHALL reach the browser unchanged.

#### Scenario: API request through the app's origin
- **WHEN** the deployed app requests `/api/catalog/products?size=1`
- **THEN** the gateway receives `/catalog/products?size=1` and its JSON answer reaches the browser

#### Scenario: Unknown API path
- **WHEN** the browser requests `/api/no/such/route`
- **THEN** the answer is the gateway's `404` problem, not the app's `index.html`

### Requirement: Deep links
Any path that is not under `/api/` and not a built file SHALL answer the app's `index.html` with `200`, so the router shows the page and a reload keeps the user on it.

#### Scenario: Reload on an inner page
- **WHEN** a logged-in customer reloads `/account/orders`
- **THEN** the order history is shown again

#### Scenario: Unknown page
- **WHEN** the browser opens `/no-such-page`
- **THEN** the app's "Page not found" page is shown

### Requirement: Caching
`index.html` SHALL be sent with `Cache-Control: no-cache`, so a new deployment is picked up on the next load. The fingerprinted files under `/assets/` SHALL be sent with a cache lifetime of one year and `immutable`.

#### Scenario: New version after a deployment
- **WHEN** the app is redeployed and the user reloads
- **THEN** the browser revalidates `index.html` and loads the new asset files

### Requirement: Same behaviour in Compose and Kubernetes
The same image SHALL serve the app in Docker Compose (host port 3000) and in Kubernetes behind the Ingress (`/`). It SHALL run as a non-root user, and SHALL report ready only when it can serve `index.html`.

#### Scenario: Journeys against Compose
- **WHEN** the browser journeys run against `http://localhost:3000` on the seeded Compose stack
- **THEN** they pass as they do against the dev server

#### Scenario: Journeys against the cluster
- **WHEN** the browser journeys run against the Ingress address of the seeded minikube cluster
- **THEN** they pass as they do against the dev server
