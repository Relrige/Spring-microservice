---
id: BL-14
title: Gateway docs still say the Ingress sends all traffic to the gateway
status: Open
importance: Nice to have
services: [docs]
blocks: —
created: 2026-10-22
---

# BL-14: Gateway docs still say the Ingress sends all traffic to the gateway

## Problem

Frontend iteration 7 (`frontend-deployment`) put the web app behind the Ingress: `/` now goes to the `frontend` Service, whose nginx forwards `/api/*` to `api-gateway:8080` with `/api` stripped. The gateway spec and the manual Kubernetes runbook still describe the old layout, so anyone following them calls `http://<ingress>/catalog/…` and gets the web app instead of the API. Nobody in the demo is blocked; readers of the docs are misled.

## Current behaviour

- [`../../../docs/design/services-specs/api-gateway-spec.md`](../../../docs/design/services-specs/api-gateway-spec.md):
  - the *Deployment* row says "the Ingress sends all external traffic here";
  - the trust-boundary paragraph says "a single Ingress rule (`/` → `api-gateway`, path unchanged)" and "the gateway accepts only the Ingress controller", and lists the components as `gateway`, `service`, `database`, `broker`;
  - *Relation to the Kubernetes Ingress* says the Ingress forwards all traffic, path unchanged, to `api-gateway`.
- `../../../e2e/manual/k8s-gateway.md` calls the gateway at `http://127.0.0.1/…` without `/api`.
- The manifests already changed (iteration 7, under a one-time exception to `frontend/AGENTS.md` §2): `k8s/ingress/ingress.yaml` (backend `frontend:8080`), `k8s/frontend/`, and `k8s/network/network-policies.yaml` (new `frontend-from-ingress-controller`; `gateway-from-ingress-controller` also admits the `frontend` component).

## Proposed change

Update the documents to the deployed layout; no code or manifest change.

## Requirements

- [ ] The gateway spec describes the Ingress → `frontend` → `/api` → `api-gateway` path, with `/api` stripped by the frontend's nginx.
- [ ] The trust-boundary paragraph lists the `frontend` component and states that the gateway accepts the frontend pods and the Ingress controller.
- [ ] `e2e/manual/k8s-gateway.md` uses `http://127.0.0.1/api/…` through the tunnel.

## Frontend impact

None. `frontend/README.md` and `k8s.txt` already describe the `/api` path.

## Notes

Related: [BL-08](BL-08-stale-customer-routing-docs.md), another stale-docs item.
