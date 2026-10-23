# Proposal

## Why

The frontend only runs through the Vite dev server, so the demo needs a developer machine with Node, and neither Docker Compose nor the minikube cluster serves the app. This last planned iteration packages the app like every service: an image, a Compose service and Kubernetes manifests, with the Ingress serving the app and the API on one origin.

## What Changes

- **Image:** `frontend/Dockerfile` builds the app (`npm ci`, `npm run build`) and serves `dist/` with unprivileged nginx on port 8080.
  - `/api/*` is proxied to the gateway with `/api` stripped, as the dev proxy does today.
  - Every other path falls back to `index.html`, so deep links and reloads work.
  - `index.html` is never cached; hashed assets are cached for a year.
- **Compose:** a `frontend` service in the root `docker-compose.yml`, on host port 3000 and depending on `api-gateway`.
- **Kubernetes:**
  - `k8s/frontend/` adds a Deployment and a ClusterIP Service.
  - The Ingress sends `/` to the frontend instead of the gateway, so the app is at `/` and the API at `/api/…`.
  - The network policies let the Ingress controller reach the frontend and the frontend reach the gateway.
  - `k8s.txt` gains the frontend image and manifests.
- **Live tests against a deployment:** the Playwright `live` project takes `E2E_BASE_URL`, so the same journeys run against Compose (`http://localhost:3000`) or the cluster, without starting Vite.

**Boundary exception, agreed for this iteration only:** `frontend/AGENTS.md` §2 keeps frontend work out of Compose and `k8s/`. This change edits the root `docker-compose.yml`, `k8s/` and `k8s.txt`, because they are this iteration's scope. Services, the gateway and `../docs/` stay untouched.

## Capabilities

### New Capabilities
- `app-hosting`: how the built app is served — one origin for the app and the API, deep links, caching, and the same behaviour in Compose and Kubernetes.

### Modified Capabilities
None. `api-access` (*Same-origin API prefix*) already describes `/api` on the page's origin; the deployment keeps it true.

## Impact

- **New files:** `frontend/Dockerfile`, `frontend/.dockerignore`, `frontend/deploy/nginx.conf`, `k8s/frontend/app-deployment.yaml`, `k8s/frontend/app-service.yaml`.
- **Changed files:** the root `docker-compose.yml`, `k8s/ingress/ingress.yaml`, `k8s/network/network-policies.yaml`, `k8s.txt`, `frontend/playwright.config.ts`, `frontend/README.md`.
- **Behaviour change in the cluster:** the gateway is no longer at the Ingress root. Clients that called `http://<ingress>/catalog/…` now call `http://<ingress>/api/catalog/…`. Compose keeps the gateway on host port 8080 as before.
- **Docs gap:** the gateway spec's *Relation to the Kubernetes Ingress* section and its trust-boundary list ("the gateway accepts only the Ingress controller") become outdated. `../docs/` stays outside the exception, so this is a new backlog item, BL-14 (Nice to have, docs).
