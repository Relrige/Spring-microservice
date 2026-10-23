---
status: DONE
service: api-gateway
---
# Route `GET /inventory/stock/{id}` (Worker Reads One Stock Item)

## Context
Inventory adds EP-INV-08, `GET /inventory/stock/{productId}`, so a worker can read one stock item with its packaging values ([Inventory task 25](../inventory-service/25-worker-reads-stock-item.md), frontend backlog [BL-03](../../../frontend/docs/backlog/BL-03-worker-reads-packaging.md)). The gateway routes it for `INVENTORY_WORKER`.
*References:* [API Gateway Spec — Route Table](../../design/services-specs/api-gateway-spec.md#route-table)

## Acceptance Criteria
- [x] Route `inventory-view-stock-item`: `GET /inventory/stock/{id}` (UUID), `Required`, role `INVENTORY_WORKER`, upstream `gateway.upstreams.inventory`.
- [x] `GET /inventory/stock/{id}/packaging` and `…/readiness` stay unrouted (internal).
- [x] Route, role-matrix and unlisted-method tests include the route; the spec's route table lists it.

## Decisions (2026-10-23)
1. Done in the same run as Inventory task 25, because the user asked for the whole feature (BL-03, option A).

## Implementation Notes (2026-10-23)
- `application.yml`: route `inventory-view-stock-item` (Method `GET`, Path with the UUID regex used by the other `{id}` routes, `TrustBoundary`, `authMode: REQUIRED`, `roles: INVENTORY_WORKER`). A non-UUID id has no route (`404` at the gateway).
- **Verified:** `RouteTableTest` (routed to Inventory, `PATCH` unlisted, `GET /inventory/stock/abc` unrouted, 32 routes parsed, the new route's access rule) and `GatewaySecuritySuiteTest` (spec route set, every-route and role-matrix tests through `RouteSamples`). `GET …/packaging` and `GET …/readiness` are still asserted as `404`. Full suite `mvnw.cmd clean test` (JDK 25): 9 test classes, 386 tests, all green.

## Deviations from spec / task
None.

*Spec sync (2026-10-23):* the route is now a row of the API Gateway spec's route table.
