---
status: DONE
service: api-gateway
---
# Route Allowlist Configuration

## Context
The gateway routes by method and path using an explicit allowlist taken from the API contracts. Anything not listed must be unreachable, which is what keeps `Internal` endpoints (payments, reservations, `batch-get`, shipments) off the public surface.
*References:* [API Gateway Spec — Route Table](../../design/services-specs/api-gateway-spec.md#route-table), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] One route entry per row of the spec's route table (YAML configuration), each with method(s), path, upstream URL (`http://<service>:<port>`, overridable by environment variables), and its auth mode and allowed roles as route metadata consumed by [task 04](04-request-pipeline-filters.md). First iteration: auth-service routes only (`POST /user/register`, `POST /auth/login`, `POST /user/non-customer`).
- [x] **Scope update (2026-10-14):** Catalog and Inventory are now implemented (their controllers match the API contracts), so their routes are added too: every catalog-service and inventory-service row of the spec's route table, with upstream URLs `GATEWAY_UPSTREAMS_CATALOG` and `GATEWAY_UPSTREAMS_INVENTORY`. Order, delivery and customer routes move to [task 10](10-remaining-service-routes.md): those services are still drafts whose paths (`/api/v1/...`) do not match the contracts yet.
- [x] Upstream ports (Kubernetes Service ports): auth `8086`, catalog `8081`, order `8082`, delivery `8083`, inventory `8084`. Payment and notification have no routes.
- [x] No wildcard routes such as `/payments/**` or `/catalog/**`. A route is a method + path pair: e.g. `PUT /inventory/stock/{id}/packaging` is routed while `GET` on the same path is not.
- [x] Path variables `{id}` are constrained to UUIDs, so `GET /catalog/products/batch-get` does not match `/catalog/products/{id}` (and later `GET /orders/fulfillment-queue` does not match `/orders/{id}`).
- [x] Paths are forwarded unchanged (no prefix stripping).
- [x] Unrouted paths and unlisted methods on a routed path return `404` (not `405`).
- [x] Tests (against stub upstreams) prove: every routed method + path reaches the right upstream; each internal catalog/inventory endpoint listed in the spec (`batch-get`, `readiness`, `GET .../packaging`, reservations) returns `404`; a non-UUID id returns `404`.

## Technical Notes / Constraints
- **Decision:** Spring Cloud Gateway MVC does not expose route `metadata` at request time, so `RouteAccessPolicy` reads the `authMode`/`roles` metadata of every route at startup (fail-fast if a route has no valid rule) and task 04's filter looks a rule up by the route id request attribute (`MvcUtils.GATEWAY_ROUTE_ID_ATTR`). `roles` is a comma-separated string and is allowed only with `REQUIRED`.
- Original note: decide how auth mode and roles are declared per route. Options: route `metadata` read by a filter (one place, mirroring "rules in one place per service"), or one filter instance per route with arguments. Metadata is preferred; record the decision.
- Keep the YAML readable: the spec table is the review checklist. Group routes by upstream, with comments.
- The carrier webhook `POST /delivery/carrier-webhook` is `Public` for now (open issue in the spec); mark it in the config with a comment.
- Route-coverage idea: a test reads the route configuration and fails if any route has no auth mode, so a route cannot be added without deciding who may call it.

## Progress Notes (2026-10-14)
- Catalog (8 routes) and inventory (3 routes) added next to the 3 auth routes; upstreams `gateway.upstreams.catalog` / `gateway.upstreams.inventory` (`GATEWAY_UPSTREAMS_CATALOG` / `GATEWAY_UPSTREAMS_INVENTORY`).
- UUID constraint is a `PathPattern` regex variable in the `Path` predicate (`{id:[0-9a-fA-F]{8}-...}`). It is repeated per route because YAML has no anchors inside predicate strings; the tests guard it.
- `RouteTableTest` now runs against three stub upstreams (shared `GatewayIntegrationTest` base, one Spring context): every route reaches the right upstream with method, path, query and body unchanged; unlisted methods, internal endpoints (also for every role), non-UUID ids and unrouted paths are `404` with no upstream call.
