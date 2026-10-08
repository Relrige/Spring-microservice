---
status: TODO
service: api-gateway
---
# Route Allowlist Configuration

## Context
The gateway routes by method and path using an explicit allowlist taken from the API contracts. Anything not listed must be unreachable, which is what keeps `Internal` endpoints (payments, reservations, `batch-get`, shipments) off the public surface.
*References:* [API Gateway Spec — Route Table](../../design/services-specs/api-gateway-spec.md#route-table), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] One route entry per row of the spec's route table (YAML configuration), each with method(s), path, upstream URL (`http://<service>:<port>`, overridable by environment variables), and its auth mode and allowed roles as route metadata consumed by [task 04](04-request-pipeline-filters.md).
- [ ] Upstream ports: auth `8086`, catalog `8081`, order `8082`, delivery `8083`, inventory `8084`. Payment and notification have no routes. Customer-service routes are defined too, but the service is not deployed yet (requests fail with an upstream error, see [task 05](05-gateway-error-handling.md)).
- [ ] No wildcard routes such as `/payments/**` or `/catalog/**`. A route is a method + path pair: e.g. `PUT /inventory/stock/{id}/packaging` is routed while `GET` on the same path is not.
- [ ] Path variables `{id}` and `{orderId}` are constrained to UUIDs, so `GET /catalog/products/batch-get` does not match `/catalog/products/{id}` and `GET /orders/fulfillment-queue` does not match `/orders/{id}`.
- [ ] Paths are forwarded unchanged (no prefix stripping).
- [ ] Unrouted paths and unlisted methods on a routed path return `404` (not `405`).
- [ ] Tests (against a stub upstream) prove: every routed method + path reaches the right upstream; each internal endpoint listed in the spec returns `404`; a non-UUID id returns `404`.

## Technical Notes / Constraints
- Decide how auth mode and roles are declared per route. Options: route `metadata` read by a filter (one place, mirroring "rules in one place per service"), or one filter instance per route with arguments. Metadata is preferred; record the decision.
- Keep the YAML readable: the spec table is the review checklist. Group routes by upstream, with comments.
- The carrier webhook `POST /delivery/carrier-webhook` is `Public` for now (open issue in the spec); mark it in the config with a comment.
- Route-coverage idea: a test reads the route configuration and fails if any route has no auth mode, so a route cannot be added without deciding who may call it.
