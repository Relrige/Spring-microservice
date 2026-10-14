---
status: DONE
service: api-gateway
---
# End-to-End Verification through the Gateway

## Context
The stub-upstream tests of [task 06](06-gateway-security-tests.md) prove the gateway in isolation. This task proves the real contracts: tokens issued by Auth Service verify in the gateway, and the trusted headers set by the gateway drive the security of the services behind it.
*References:* [API Gateway Spec — Auth Service Interaction](../../design/services-specs/api-gateway-spec.md#auth-service-interaction), [Auth Service Spec](../../design/services-specs/auth-service-spec.md), [Manual e2e runbooks](../../../e2e/manual/README.md)

**Revised 2026-10-14:** manual end-to-end checks are now recorded as step-by-step runbooks in `e2e/manual/` (Git Bash, `curl`), not as Postman collections. Catalog and Inventory are implemented and routed ([task 03](03-route-table.md)), so the checks cover them as well as Auth Service.

## Acceptance Criteria
- [x] Runbook `e2e/manual/docker-compose-gateway.md` (gateway + auth, catalog, inventory, their databases and Kafka), every request sent **through the gateway only** (`localhost:8080`), with expected results:
  - Auth: `POST /user/register` → `201`; duplicate email → `409` (passed through from Auth Service); `POST /auth/login` → `200` with a token; wrong password → `401` "Invalid credentials" (from Auth Service, not the gateway).
  - `POST /user/non-customer`: no token → `401` (gateway); customer token → `403` (gateway); seeded admin token → `201`; expired or garbled token → `401`.
  - The admin creates a `CATALOG_MANAGER` and an `INVENTORY_WORKER`; with their tokens: create a category and a product (Catalog), adjust its stock (Inventory), and read the product as an anonymous guest (`stockStatus` changes via Kafka).
  - Wrong roles are rejected by the gateway (`403`): a customer creating a product, a catalog manager adjusting stock.
  - A forged `X-User-Role: ADMIN` / `X-User-Id` header, with a customer token or without a token, does not elevate privileges.
  - Internal endpoints (`GET /catalog/products/batch-get`, `GET /inventory/stock/{id}/readiness`, `POST /inventory/reservations`, `POST /payments/charge`), `/actuator/...` and other unrouted paths → `404`.
  - `GET /catalog/products` with a garbled token → `401` (Optional route is strict); `GET /catalog/products/{id}` with a garbled token → `200` (Public route ignores it).
- [x] Runbook `e2e/manual/k8s-gateway.md`: the same checks in minikube through the Ingress (`minikube tunnel`), plus the network checks of [task 08](08-ingress-and-trust-boundary.md).
- [x] Both runbooks are listed in `e2e/manual/README.md` with their last run date and result; the results are recorded in this task's notes.
- [x] Where reality diverged from the spec (WebMVC variant, route-metadata decision, `AUTH_JWT_SECRET` name, management port, deferred routes), the deviations are listed in this task's notes and the [API Gateway Spec](../../design/services-specs/api-gateway-spec.md) is updated to match (2026-10-14).

## Technical Notes / Constraints
- `AUTH_JWT_SECRET` in `api-gateway/.env` and `auth-service/.env` must match (and in `gateway-secrets` / `auth-secrets`).
- The existing Catalog ↔ Inventory runbooks call the services directly with hand-made trusted headers. That still works in Compose (services publish host ports) and in minikube via `kubectl port-forward` (port-forward traffic enters the pod directly and is not filtered by NetworkPolicy); they test the services, this task tests the edge.
- Order, delivery and customer routes cannot be verified end to end yet ([task 10](10-remaining-service-routes.md)).

## Progress Notes (2026-10-14)
### Compose run (`e2e/manual/docker-compose-gateway.md`)
Run from a fresh Compose project (new volumes). All expected results matched:
- Register `201`, duplicate `409` with `"service":"auth-service"`; login `200`; wrong password `401` "Invalid credentials" from Auth Service.
- `POST /user/non-customer`: no token `401`, customer `403`, garbled `401`, tampered admin token `401` (all `"service":"api-gateway"`, detail without a reason); admin `201`. Forged `X-User-Role: ADMIN` with a customer token `403`, without a token `401`.
- Customer and admin creating a category `403` (roles are not hierarchical); manager reading stock `403`.
- Manager created category and product; guest `GET /catalog/products/{id}` `404` while `NOT_ACTIVE`, manager sees it with `?status=NOT_ACTIVE` (Optional route forwards the identity). `ProductCreated` reached Inventory; worker set packaging (`204`) and stock (`{"newAvailableQuantity":5}`); manager activated (`200`, Catalog → Inventory readiness call); guest then sees `stockStatus: IN_STOCK`.
- `GET /catalog/products` with a garbled token `401`; `GET /catalog/products/{id}` with it `200`.
- `404` for `batch-get`, `readiness`, `GET .../packaging`, `POST /inventory/reservations`, `POST /payments/charge`, `/actuator/health`, `/actuator/env`, an unknown path, `DELETE /catalog/products/{id}`, a non-UUID id — also with an admin token.
- Inventory stopped → `502` "The upstream service is unavailable." (gateway logs the host and cause at WARN).

### Kubernetes run (`e2e/manual/k8s-gateway.md`)
See [task 08](08-ingress-and-trust-boundary.md#progress-notes-2026-10-14): Ingress → gateway checks, the full business flow under NetworkPolicies, and the in-cluster probes.

### Deviations from the original spec (spec updated 2026-10-14)
- **Gateway variant:** Spring Cloud Gateway **Server WebMVC** (servlet stack), not the reactive gateway the spec implies ([task 01](01-scaffold-api-gateway.md)).
- **Secret name:** the variable is `AUTH_JWT_SECRET` (property `auth.jwt.secret`), the same name as in auth-service, not `JWT_SECRET`.
- **Pipeline wiring:** auth mode and roles are route `metadata`, read at startup by `RouteAccessPolicy`; the pipeline is one `TrustBoundary` route filter that every route must declare (startup fails otherwise). Pipeline order differs formally: header stripping happens after route matching (unmatched requests are `404` and never forwarded, so the effect is the same).
- **Actuator** runs on a separate management port `8090`, not exposed by the Service or Compose.
- **Route table:** only auth, catalog and inventory are routed; order, delivery and customer routes are deferred to [task 10](10-remaining-service-routes.md).
- **Ingress:** no TLS termination in the local setup.
- **Timeouts:** connect `2s`, read `15s` via `spring.http.clients.*` (not mentioned in the spec).
