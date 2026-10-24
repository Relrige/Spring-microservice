---
status: DONE
service: api-gateway
---
# Routes for Order, Delivery and Customer Services

## Context
**Blocked** until the services below implement their contract paths. [Task 03](03-route-table.md) routes every row of the spec's route table whose service is implemented (auth, catalog, inventory). Order and Delivery Service are still drafts whose endpoints (`/api/v1/orders`, `/api/v1/delivery`) do not match the API contracts, and Customer Service does not exist. Their routes are added once each service exposes the contract paths, so the route table never points at endpoints that do not exist.

**Update (2026-10-15): the Delivery part is unblocked.** Delivery implements its contract paths ([Delivery task 16](../delivery-service/16-containerization-and-deployment.md)): `POST /delivery/orders/{orderId}/ttn/retry` (role `INVENTORY_WORKER`) is the only route to add; Delivery listens on container port 8080 (Compose host port 8083, Kubernetes Service port 8083). Order and Customer remain blocked.

**Update (2026-10-16): the Customer part is unblocked.** Customer Service implements all its contract paths ([Customer task 09](../customer-service/09-containerization-and-deployment.md)); it listens on container port 8080 (Compose host port 8087, Kubernetes Service port 8087). Every route requires role `CUSTOMER`: `GET`/`PUT /customers/me/profile`, `GET`/`PUT`/`DELETE /customers/cart`, `POST /customers/cart/merge`, `POST /customers/cart/items`, `PATCH`/`DELETE /customers/cart/items/{productId}`. Build them from the [API Contracts](../../design/services-requirements/api-contracts.md): the gateway spec's route table still lacks `PUT` and `DELETE /customers/cart`. Order remains blocked.

**Update (2026-10-17): the Order part is unblocked.** Order implements all its contract paths (Order tasks 07–15, [Order task 16](../order-service/16-containerization-and-deployment.md)); it listens on container port 8080 (Compose host port 8082, Kubernetes Service port 8082). Customer routes need role `CUSTOMER` and the `X-User-Email` header ([task 11](11-forward-user-email-header.md)); `GET /orders/fulfillment-queue` and `POST /orders/{id}/ship` need `INVENTORY_WORKER`. The queue now answers `{ deliveryDataAvailable, orders }` (see the API contracts).
*References:* [API Gateway Spec — Route Table](../../design/services-specs/api-gateway-spec.md#route-table), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] Order-service routes from the spec table (`/orders/validate`, `/orders`, `/orders/{id}`, `/orders/{id}/pay`, `/orders/fulfillment-queue`, `/orders/{id}/ship`) with upstream `GATEWAY_UPSTREAMS_ORDER` (`http://order-service:8082`). `{id}` is a UUID, so `GET /orders/fulfillment-queue` does not match `GET /orders/{id}`.
- [x] Delivery-service routes (`POST /delivery/orders/{id}/ttn/retry`; the carrier webhook was dropped from the contracts on 2026-10-15, see the Delivery spec) with upstream `GATEWAY_UPSTREAMS_DELIVERY` (`http://delivery-service:8083`). `GET /delivery/shipments` and `DELETE /delivery/shipments/{orderId}` stay unrouted.
- [x] Customer-service routes (the nine routes listed in the update above, all `CUSTOMER`) with upstream `GATEWAY_UPSTREAMS_CUSTOMER` (`http://customer-service:8087`).
- [x] The parameterized route, role-matrix and internal-endpoint tests of [task 06](06-gateway-security-tests.md) include the new routes; the ConfigMap, `.env.example` and Compose get the new upstream URLs.
- [x] Each service's routes are checked end to end through the gateway (extend the [task 09](09-end-to-end-verification.md) runbooks).

## Decisions (2026-10-23)
1. The remaining work runs on the branch `api-gateway/remaining-routes-e2e`.
2. "Checked end to end": the [Compose gateway runbook](../../../e2e/manual/docker-compose-gateway.md) gets a Customer and Delivery step (roles, profile, cart, TTN retry); Order stays covered by the [Order runbook](../../../e2e/manual/docker-compose-order.md) and is linked, not repeated. The [Kubernetes gateway runbook](../../../e2e/manual/k8s-gateway.md) deploys every service and repeats these checks through the Ingress. Both are run.
3. Since the frontend deployment (frontend `BL-14`), the Ingress sends `/` to the frontend, which forwards `/api/*` to the gateway with `/api` stripped. The Kubernetes runbook and the gateway spec are updated to that layout in this branch (the spec as part of the spec sync).
4. Compose runs first with minikube stopped, then the existing Calico cluster with Compose stopped; the cluster is not re-created.
5. Catalog task 20 and Order task 16 are closed on their own branches, not here.

## Technical Notes / Constraints
- Add one service at a time, together with (or right after) the service task that implements its contract paths.
- ~~Guest access to orders (`?token=`) is checked by Order Service.~~ Since 2026-10-16 only registered customers can order: every customer `/orders` route is `Required` with role `CUSTOMER` (see the updated route table in the gateway spec); worker routes stay `INVENTORY_WORKER`. The `X-User-Email` header the Order routes need is [task 11](11-forward-user-email-header.md).

## Progress Notes: Delivery and Customer (2026-10-17)
- Delivery (1 route) and Customer (9 routes, one per method + path pair) added; upstreams `gateway.upstreams.delivery` / `gateway.upstreams.customer`. The ConfigMap uses the Kubernetes Service ports (`8083`, `8087`); `.env.example` (read by Compose through `env_file`) uses container port `8080`, like the other upstreams.
- `{productId}` is constrained to a UUID like `{id}`: a malformed product id is a `404` at the gateway instead of reaching Customer Service. The spec's route table now has the missing `PUT`/`DELETE /customers/cart` row and names the variable `{productId}`.
- `RouteTableTest` covers the new routes (routing, unlisted methods, non-UUID ids, unrouted prefixes); `GatewaySecuritySuiteTest` lists them in its spec route set and runs its route, role-matrix and header tests on them through `RouteSamples`. The two internal Delivery endpoints were already asserted as `404`.
- Open: the end-to-end runbook checks for Delivery and Customer.

## Progress Notes: Order (2026-10-17)
- Seven routes (`order-validate`, `order-submit`, `order-history`, `order-details-or-cancel` for `GET`/`DELETE`, `order-pay`, `order-fulfillment-queue`, `order-ship`), all `REQUIRED`: customer routes `CUSTOMER`, queue and ship `INVENTORY_WORKER`. `{id}` is a UUID, so `/orders/fulfillment-queue` never matches `/orders/{id}`.
- Upstream `gateway.upstreams.order`: `http://order-service:8080` in `.env.example` (Compose, container port), `http://order-service:8082` in the ConfigMap (Kubernetes Service port). The criterion's `:8082` applies to Kubernetes only.
- `RouteTableTest` (routing, unlisted methods, non-UUID ids, unrouted paths), `GatewaySecuritySuiteTest` (spec route set, every-route and role-matrix tests through `RouteSamples`) and the upstream-failure and management-port tests include Order.
- End to end: [docker-compose-order.md](../../../e2e/manual/docker-compose-order.md), run successfully on 2026-10-17.
- Open: the Kubernetes run for Order.

## Implementation Notes (2026-10-23)
- **Tests:** no code change was needed. The full suite (`.\mvnw.cmd test`, JDK 25) ran 377 tests, 0 failures; `RouteTableTest` (97) and `GatewaySecuritySuiteTest` (53) cover the Order, Delivery and Customer routes, their unlisted methods and non-UUID ids, and the internal Delivery endpoints as `404`. The upstream URLs were already in `api-gateway/.env.example` (read by Compose) and `k8s/gateway/app-config.yaml`.
- **Compose runbook:** [docker-compose-gateway.md](../../../e2e/manual/docker-compose-gateway.md) gets step 5, *Customer and Delivery through the gateway*: role checks (`401` without a token, `403` for worker and admin on the cart, `403` for a customer on the TTN retry), a non-UUID `{productId}` as `404` at the gateway, profile read/save/`400` on a bad phone, cart add/change/remove, and a TTN retry for an order without a shipment answered `404` by Delivery. The two internal Delivery endpoints joined the step-7 list of unrouted paths. Order stays covered by the [Order runbook](../../../e2e/manual/docker-compose-order.md).
- **Kubernetes runbook:** [k8s-gateway.md](../../../e2e/manual/k8s-gateway.md) now deploys every service and the frontend, and describes the Ingress → frontend → `/api` → gateway path (decision 3): `G=http://127.0.0.1/api`, the app at the Ingress root, and a new probe showing that a pod labelled `frontend` reaches the gateway but no service.
- **Verified:**
  - Compose (2026-10-23): steps 2–8 run as a script against Auth, Catalog, Inventory, Customer and Delivery; every expected status matched, including `502` with Inventory stopped. The gateway was published on host port 18080 through a local override file, because a Windows service held 8080; nothing in the repository changed for that.
  - Kubernetes (2026-10-23): a separate minikube profile `voltstore-np` with Calico, all images from the current Compose builds, the secrets copied from the team's cluster. Step 3 through `minikube tunnel` (API `200`/`404` under `/api`, the app's `index.html` at the root, `/healthz` `200`); steps 2–7 of the Compose runbook through the Ingress with all checks matching; step 8 (`502` with Inventory scaled to 0); step 4: an unlabelled pod reaches nothing (`000`, Postgres and Kafka blocked), a `frontend` pod reaches the gateway (`200`) but not Catalog (`000`), a `gateway` pod reaches Catalog (`200`), a `service` pod reaches Kafka and Inventory (`401`) but not Catalog's database; port-forward still works.
  - The Order flow through the cluster's Ingress was covered on 2026-10-22 by the frontend's live browser journeys (checkout, payment, fulfillment queue, shipping), which go through `/api` to every Order route.

## Deviations from spec / task
- **Runbook step 6 was out of date.** It expected `200` for `GET /catalog/products/{id}` with a garbled token, but [task 12](12-optional-product-details-route.md) made the route `Optional`, so `401` is correct. The step now shows both Optional routes rejecting the token and uses `GET /catalog/categories` as the Public example. Alternative: drop the Public example; kept, because the Optional/Public difference is what the step teaches.
- **The team's default minikube cluster runs kindnet, not Calico,** so it silently ignores NetworkPolicy (decision 4 assumed Calico). Instead of re-creating it, the runbook's existing option of a second profile (`voltstore-np`) was used, which leaves the team's cluster and its data untouched. The runbook's step 0 now says that a Calico cluster created before can just be started again.
- **The Kubernetes Order check** is not repeated with `curl` in the runbook; it points at the frontend's live journeys, which exercise every Order route through the same Ingress path. Alternative: copy the Order runbook's flow into the Kubernetes runbook; not done, to avoid a third copy of the same steps.

*Spec sync (2026-10-23):* the Ingress → frontend → `/api` → gateway path (decision 3), the `frontend` policy component and the Customer upstream address are now part of the API Gateway spec (*Relation to the Kubernetes Ingress*, *Configuration*, trust boundary).
