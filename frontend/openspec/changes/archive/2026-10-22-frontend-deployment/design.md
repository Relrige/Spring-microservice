# Design

## Context

- **Today:** the app runs only through Vite. The dev proxy maps `/api/*` to the gateway with `/api` stripped (`vite.config.ts`), and `npm run build` produces `dist/`, with the mock off unless `VITE_API_MOCK=true`.
- **Root `docker-compose.yml`:** every service builds from its folder; the gateway publishes host port 8080.
- **`k8s/`:** one folder per component (`app-deployment.yaml`, `app-service.yaml`, and config and secret where needed).
  - Images are `voltstore/<name>:latest`, loaded with `minikube image load` (`k8s.txt`), and pods run as non-root (`runAsUser: 10001`).
  - The Ingress (`k8s/ingress/ingress.yaml`, class `nginx`) sends `/` to `api-gateway:8080` unchanged.
  - `k8s/network/network-policies.yaml` denies all ingress by default and lets only the `ingress-nginx` namespace reach the gateway.
  - Policies select pods by `app.kubernetes.io/component`.
- **Decided with the user:**
  - a one-time exception to `frontend/AGENTS.md` §2, so this iteration may edit Compose, `k8s/` and `k8s.txt`;
  - the app at `/` and the API at `/api` through the frontend's nginx.

## Goals / Non-Goals

**Goals:**
- One image for Compose and Kubernetes, with no build-time API address: the `/api` proxy and the gateway's Service name `api-gateway` are the same in both.
- The existing browser journeys prove both deployments.

**Non-Goals:**
- TLS, a CDN, or more than one replica.
- Changing the gateway or its routes.
- Updating `../docs/`, which goes to BL-14.

## Decisions

### D1. Image
- **Build stage:** `node:22.22-alpine` runs `npm ci` then `npm run build`. The type check and tests run in CI and locally, not in the image.
- **Runtime stage:** `nginxinc/nginx-unprivileged:1.27-alpine`, listening on 8080 as uid 101. Exact tags are pinned at implementation, at least two weeks old.
- **Contents:** `deploy/nginx.conf` is copied in as `/etc/nginx/conf.d/default.conf`, and `dist/` into the web root.
- **`.dockerignore`:** excludes `node_modules`, `dist`, test reports, `e2e`, `openspec` and `docs`.

### D2. nginx configuration
- `location /api/ { proxy_pass http://api-gateway:8080/; }`: the trailing slash strips `/api`. It sets `Host`, `X-Forwarded-For` and `X-Forwarded-Proto`, with timeouts above Payment's worst case (`proxy_read_timeout 30s`).
- Client-supplied identity headers need no stripping here: the gateway already removes `X-User-*` and `X-Internal-Token` from every request (gateway spec, trust boundary).
- `location /assets/ { … expires 1y; add_header Cache-Control "public, max-age=31536000, immutable"; }`
- `location / { try_files $uri /index.html; add_header Cache-Control "no-cache"; }`
- `location = /healthz { return 200; }` for the probes, without logging.
- The upstream name is resolved at startup. In Kubernetes the Service exists before the pod. In Compose, `depends_on: api-gateway` orders the start.

### D3. Compose
A `frontend` service with `build: ./frontend`, `container_name: frontend`, `ports: "3000:8080"` and `depends_on: [api-gateway]`. Host port 3000 is free in the current stack (8080 is the gateway, 5173 and 5174 are Vite).

### D4. Kubernetes
- **`k8s/frontend/app-deployment.yaml`:**
  - one replica, image `voltstore/frontend:latest` (`IfNotPresent`);
  - labels `app: frontend`, `app.kubernetes.io/part-of: voltstore`, `app.kubernetes.io/component: frontend`;
  - `runAsNonRoot`, uid 101;
  - small resources (requests 50m CPU and 32Mi, limits 200m and 64Mi);
  - readiness and liveness probes on `/healthz`.
- **`k8s/frontend/app-service.yaml`:** ClusterIP `frontend:8080`.
- **Ingress:** the single rule's backend becomes `frontend:8080`; the comment explains that the API goes through the frontend's `/api` proxy.
- **Network policies:**
  - new `frontend-from-ingress-controller`, letting namespace `ingress-nginx` reach the `frontend` component on 8080;
  - `gateway-from-ingress-controller` gains the `frontend` component as a source, and keeps the controller, so direct debugging through a port-forward still works.
- **`k8s.txt`:** `minikube image load voltstore/frontend:latest` and `kubectl apply -f k8s\frontend\`, placed before the Ingress.

### D5. Live tests against a deployment
- In `playwright.config.ts`, the `live` project uses `E2E_BASE_URL` when it is set, and then starts no web server. Without it, it behaves as now (Vite on 5173).
- `npm run seed` keeps targeting the gateway directly (`SEED_API_URL`).
  - **Compose:** the default, `http://localhost:8080`.
  - **Cluster:** `http://localhost/api` through the tunnel. The seed's base URL already takes a path prefix.

## Risks / Trade-offs

- **[The gateway moves off the Ingress root]** → `curl` habits and any tool aimed at `http://<ingress>/catalog/…` must add `/api`. The README states it, and BL-14 asks for the gateway spec to follow.
- **[An nginx proxy hop adds latency and a timeout]** → the timeouts are set above the gateway's and Payment's (D2). This is a small cost in the demo.
- **[The minikube clock drift and the stale database volumes seen in earlier runs]** → the cluster run may need the known fixes from the team's notes (widen time ranges, reset volumes). Each one is a person's call.
- **[Two images to rebuild for a frontend change]** → only the frontend image changes. `k8s.txt` lists the one command.

## Migration Plan

- **Deploy:** in Compose, `docker compose up -d --build frontend`. In the cluster, load the image, apply `k8s/frontend/`, then re-apply the network policies and the Ingress.
- **Rollback:** re-apply the previous `ingress.yaml` and policies; the gateway is back at `/`.
