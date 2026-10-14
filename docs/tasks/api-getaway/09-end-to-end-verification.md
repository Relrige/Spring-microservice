---
status: TODO
service: api-gateway
---
# End-to-End Verification through the Gateway

## Context
The stub-upstream tests of [task 06](06-gateway-security-tests.md) prove the gateway in isolation. This task proves the real contracts: tokens issued by Auth Service verify in the gateway, and the trusted headers set by the gateway drive the security of the services behind it.
*References:* [API Gateway Spec — Auth Service Interaction](../../design/services-specs/api-gateway-spec.md#auth-service-interaction), [Auth Service Spec](../../design/services-specs/auth-service-spec.md), [Manual e2e runbooks](../../../e2e/manual/README.md)

**Revised 2026-10-14:** manual end-to-end checks are now recorded as step-by-step runbooks in `e2e/manual/` (Git Bash, `curl`), not as Postman collections. Catalog and Inventory are implemented and routed ([task 03](03-route-table.md)), so the checks cover them as well as Auth Service.

## Acceptance Criteria
- [ ] Runbook `e2e/manual/docker-compose-gateway.md` (gateway + auth, catalog, inventory, their databases and Kafka), every request sent **through the gateway only** (`localhost:8080`), with expected results:
  - Auth: `POST /user/register` → `201`; duplicate email → `409` (passed through from Auth Service); `POST /auth/login` → `200` with a token; wrong password → `401` "Invalid credentials" (from Auth Service, not the gateway).
  - `POST /user/non-customer`: no token → `401` (gateway); customer token → `403` (gateway); seeded admin token → `201`; expired or garbled token → `401`.
  - The admin creates a `CATALOG_MANAGER` and an `INVENTORY_WORKER`; with their tokens: create a category and a product (Catalog), adjust its stock (Inventory), and read the product as an anonymous guest (`stockStatus` changes via Kafka).
  - Wrong roles are rejected by the gateway (`403`): a customer creating a product, a catalog manager adjusting stock.
  - A forged `X-User-Role: ADMIN` / `X-User-Id` header, with a customer token or without a token, does not elevate privileges.
  - Internal endpoints (`GET /catalog/products/batch-get`, `GET /inventory/stock/{id}/readiness`, `POST /inventory/reservations`, `POST /payments/charge`), `/actuator/...` and other unrouted paths → `404`.
  - `GET /catalog/products` with a garbled token → `401` (Optional route is strict); `GET /catalog/products/{id}` with a garbled token → `200` (Public route ignores it).
- [ ] Runbook `e2e/manual/k8s-gateway.md`: the same checks in minikube through the Ingress (`minikube tunnel`), plus the network checks of [task 08](08-ingress-and-trust-boundary.md).
- [ ] Both runbooks are listed in `e2e/manual/README.md` with their last run date and result; the results are recorded in this task's notes.
- [ ] `docs/` updated where reality diverged from the spec (WebMVC variant, route-metadata decision, `AUTH_JWT_SECRET` name, management port, deferred routes).

## Technical Notes / Constraints
- `AUTH_JWT_SECRET` in `api-gateway/.env` and `auth-service/.env` must match (and in `gateway-secrets` / `auth-secrets`).
- The existing Catalog ↔ Inventory runbooks call the services directly with hand-made trusted headers. That still works in Compose (services publish host ports) and in minikube via `kubectl port-forward` (port-forward traffic enters the pod directly and is not filtered by NetworkPolicy); they test the services, this task tests the edge.
- Order, delivery and customer routes cannot be verified end to end yet ([task 10](10-remaining-service-routes.md)).
