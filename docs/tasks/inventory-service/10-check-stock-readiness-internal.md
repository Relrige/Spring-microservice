---
status: TODO
service: inventory-service
---
# EP-INV-04: Check Stock Readiness (Internal)

## Context
Catalog Service calls this endpoint before activating a product. It reports whether a `StockItem` exists and packaging is complete. A "not ready" result is an expected business answer, not an error, so it is returned as `422` with a machine-readable `reason`, which Catalog passes to the manager.
*References:* [Inventory Spec — EP-INV-04](../../design/services-specs/inventory-service-spec.md), [Catalog Spec — EP-CAT-03](../../design/services-specs/catalog-service-spec.md), [Catalog readiness client task](../catalog-service/21-inventory-readiness-client.md)

## Acceptance Criteria
- [ ] `GET /inventory/stock/{productId}/readiness` returns `200 OK { ready: true }` when the `StockItem` exists and all four packaging values are non-null and `> 0`.
- [ ] No `StockItem` → `422 { reason: "stock_item_not_found" }` (not `404`).
- [ ] `StockItem` exists but any packaging value is null or `≤ 0` → `422 { reason: "packaging_dimensions_missing" }`.
- [ ] Both `422` bodies use the common `ProblemDetail` shape with the extra `reason` property ([task 03](03-error-handling-and-validation.md)).
- [ ] Non-UUID `productId` → `400`.
- [ ] Route rule `GET /inventory/stock/{productId}/readiness` → `hasRole("SERVICE")`. Anonymous → `401`; a user with any role (even `ADMIN`) → `403`; valid `X-Internal-Token` → success. The endpoint is not routed by the gateway.
- [ ] Read-only: it never creates a `StockItem` and emits no event.
- [ ] Tests: service tests for the three outcomes; controller/integration tests including security cases.

## Technical Notes / Constraints
- Reuse `packagingConfigured` from [task 02](02-stock-item-entity-and-persistence.md); do not reimplement the rule.
- The reason strings are part of the contract with Catalog; define them as constants in one place and keep them stable (Catalog passes unknown reasons through, but the documented two must not change).
- The route-coverage test must exclude this internal-only route (see [task 07](07-trusted-header-security.md)).
