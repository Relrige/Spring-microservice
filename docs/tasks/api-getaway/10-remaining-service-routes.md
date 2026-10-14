---
status: TODO
service: api-gateway
---
# Routes for Order, Delivery and Customer Services

## Context
**Blocked** until the services below implement their contract paths. [Task 03](03-route-table.md) routes every row of the spec's route table whose service is implemented (auth, catalog, inventory). Order and Delivery Service are still drafts whose endpoints (`/api/v1/orders`, `/api/v1/delivery`) do not match the API contracts, and Customer Service does not exist. Their routes are added once each service exposes the contract paths, so the route table never points at endpoints that do not exist.
*References:* [API Gateway Spec — Route Table](../../design/services-specs/api-gateway-spec.md#route-table), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] Order-service routes from the spec table (`/orders/validate`, `/orders`, `/orders/{id}`, `/orders/{id}/pay`, `/orders/fulfillment-queue`, `/orders/{id}/ship`) with upstream `GATEWAY_UPSTREAMS_ORDER` (`http://order-service:8082`). `{id}` is a UUID, so `GET /orders/fulfillment-queue` does not match `GET /orders/{id}`.
- [ ] Delivery-service routes (`POST /delivery/orders/{id}/ttn/retry`, `POST /delivery/carrier-webhook` — `PUBLIC`, marked with a comment as an open issue) with upstream `GATEWAY_UPSTREAMS_DELIVERY` (`http://delivery-service:8083`). `GET /delivery/shipments` and `DELETE /delivery/shipments/{orderId}` stay unrouted.
- [ ] Customer-service routes once the service exists (upstream URL and port decided with the service).
- [ ] The parameterized route, role-matrix and internal-endpoint tests of [task 06](06-gateway-security-tests.md) include the new routes; the ConfigMap, `.env.example` and Compose get the new upstream URLs.
- [ ] Each service's routes are checked end to end through the gateway (extend the [task 09](09-end-to-end-verification.md) runbooks).

## Technical Notes / Constraints
- Add one service at a time, together with (or right after) the service task that implements its contract paths.
- Guest access to orders (`?token=`) is checked by Order Service; the gateway only forwards the query string unchanged (already covered by task 06).
