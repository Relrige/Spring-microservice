---
status: DONE
service: inventory-service
---
# EP-INV-04: Check Stock Readiness (Internal)

## Context
Catalog Service calls this endpoint before activating a product. It reports whether a `StockItem` exists and packaging is complete. A missing `StockItem` is `404`; incomplete packaging is `422` with a machine-readable `reason`, which Catalog passes to the manager.
*References:* [Inventory Spec — EP-INV-04](../../design/services-specs/inventory-service-spec.md), [Catalog Spec — EP-CAT-03](../../design/services-specs/catalog-service-spec.md), [Catalog readiness client task](../catalog-service/21-inventory-readiness-client.md)

## Acceptance Criteria
- [x] `GET /inventory/stock/{productId}/readiness` returns `204 No Content` when the `StockItem` exists and all four packaging values are non-null and `> 0`.
- [x] No `StockItem` → `404` via `StockItemNotFoundException`.
- [x] `StockItem` exists but any packaging value is null or `≤ 0` → `422 { reason: "packaging_data_missing" }` via `PackagingNotConfiguredException`.
- [x] Both error bodies use the common `ProblemDetail` shape ([task 03](03-error-handling-and-validation.md)); the `422` carries the extra `reason` property.
- [x] Non-UUID `productId` → `400`.
- [x] Route rule `GET /inventory/stock/{productId}/readiness` → `hasRole("SERVICE")`. Anonymous (or invalid token) → `401`; a user with any role (even `ADMIN`) → `403`; valid `X-Internal-Token` → success. The endpoint is not routed by the gateway.
- [x] Read-only: it never creates a `StockItem` and emits no event.
- [x] Tests: service tests for the three outcomes; controller/integration tests including security cases.

## Technical Notes / Constraints
- Reuse `packagingConfigured` from [task 02](02-stock-item-entity-and-persistence.md); do not reimplement the rule.
- The reason string is part of the contract with Catalog; it is defined in one place (the `PackagingNotConfiguredException` handler in `GlobalExceptionHandler`) and must stay stable.
- Route coverage: `RouteSecurityRulesTest` probes every route with each user role **and** the internal-service caller, so this internal-only route is covered without an exclusion.

## Deviations from spec / task
Agreed on 2026-10-11, following the deviations already recorded in [task 03](03-error-handling-and-validation.md):
- Success is `204 No Content` with no body (spec: `200 OK { ready: true }`). Catalog treats the status alone as "ready".
- A missing `StockItem` is `404` via `StockItemNotFoundException`, the same as elsewhere in the service (spec: `422 { reason: "stock_item_not_found" }`).
- The packaging reason is `packaging_data_missing` (spec: `packaging_dimensions_missing`), because the check covers gross weight as well as dimensions.
- [Catalog task 21](../catalog-service/21-inventory-readiness-client.md) is updated to this contract.

## Implementation
- `StockService.checkReadiness(productId)` (`@Transactional(readOnly = true)`): `findById` → `StockItemNotFoundException`; `!isPackagingConfigured()` → `PackagingNotConfiguredException`.
- `StockController` `GET /{productId}/readiness` → `204`.
- `SecurityConfig`: `GET /inventory/stock/{productId}/readiness` → `hasRole("SERVICE")`.
- Tests: `StockServiceReadinessTest`, `StockReadinessControllerTest`, `StockReadinessIntegrationTest`.
- *Sprint-end spec update (2026-10-12):* these deviations are now part of the [Inventory spec](../../design/services-specs/inventory-service-spec.md) and `api-contracts.md`.
