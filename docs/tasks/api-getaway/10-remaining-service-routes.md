---
status: TODO
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
- [ ] Order-service routes from the spec table (`/orders/validate`, `/orders`, `/orders/{id}`, `/orders/{id}/pay`, `/orders/fulfillment-queue`, `/orders/{id}/ship`) with upstream `GATEWAY_UPSTREAMS_ORDER` (`http://order-service:8082`). `{id}` is a UUID, so `GET /orders/fulfillment-queue` does not match `GET /orders/{id}`.
- [ ] Delivery-service routes (`POST /delivery/orders/{id}/ttn/retry`; the carrier webhook was dropped from the contracts on 2026-10-15, see the Delivery spec) with upstream `GATEWAY_UPSTREAMS_DELIVERY` (`http://delivery-service:8083`). `GET /delivery/shipments` and `DELETE /delivery/shipments/{orderId}` stay unrouted.
- [ ] Customer-service routes (the nine routes listed in the update above, all `CUSTOMER`) with upstream `GATEWAY_UPSTREAMS_CUSTOMER` (`http://customer-service:8087`).
- [ ] The parameterized route, role-matrix and internal-endpoint tests of [task 06](06-gateway-security-tests.md) include the new routes; the ConfigMap, `.env.example` and Compose get the new upstream URLs.
- [ ] Each service's routes are checked end to end through the gateway (extend the [task 09](09-end-to-end-verification.md) runbooks).

## Technical Notes / Constraints
- Add one service at a time, together with (or right after) the service task that implements its contract paths.
- ~~Guest access to orders (`?token=`) is checked by Order Service.~~ Since 2026-10-16 only registered customers can order: every customer `/orders` route is `Required` with role `CUSTOMER` (see the updated route table in the gateway spec); worker routes stay `INVENTORY_WORKER`. The `X-User-Email` header the Order routes need is [task 11](11-forward-user-email-header.md).
