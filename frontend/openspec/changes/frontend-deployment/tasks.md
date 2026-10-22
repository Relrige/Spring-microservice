# Tasks

Root files (`docker-compose.yml`, `k8s/`, `k8s.txt`) are edited under the exception agreed for this iteration only (proposal). Services, the gateway and `../docs/` stay untouched.

## 1. Image

- [x] 1.1 Add `frontend/Dockerfile`, `frontend/.dockerignore` and `frontend/deploy/nginx.conf` (design D1–D2); verify `docker build -t voltstore/frontend:latest frontend` succeeds and, run alone with a stub upstream, `/healthz` answers 200, `/account/orders` answers `index.html` with `Cache-Control: no-cache` and an `/assets/` file answers with `max-age=31536000, immutable`

## 2. Compose

- [x] 2.1 Add the `frontend` service to the root `docker-compose.yml` (design D3) and let the Playwright `live` project take `E2E_BASE_URL` (design D5); verify on the seeded Compose stack that `/api/catalog/products?size=1` through port 3000 answers the gateway's JSON, `/api/no/such/route` the gateway's `404` problem, and `E2E_BASE_URL=http://localhost:3000 npm run test:e2e:live` passes (*Journeys against Compose*)

## 3. Kubernetes

- [x] 3.1 Add `k8s/frontend/` (Deployment, Service), point the Ingress at the frontend, extend the network policies and `k8s.txt` (design D4); verify with `kubectl apply --dry-run=server` (or `--dry-run=client` when no cluster runs) that every manifest is valid
- [x] 3.2 Deploy to minikube (ask a person before starting or resetting the cluster), seed it through the tunnel and run the live journeys with `E2E_BASE_URL` set to the Ingress address; verify they pass (*Journeys against the cluster*) and that a reload of `/account/orders` keeps the page

## 4. Docs and backlog

- [ ] 4.1 Add BL-14 (gateway spec: the Ingress now serves the frontend; Nice to have, docs) with its index row; document Compose and Kubernetes use in `frontend/README.md` (ports, the `/api` path through the Ingress, `E2E_BASE_URL`) and record both runs in *Live verification*; verify the links in the README resolve

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive` so `app-hosting` becomes a main spec.
- Restore `frontend/AGENTS.md` §2 as the rule for later work: the exception covered this iteration only.
