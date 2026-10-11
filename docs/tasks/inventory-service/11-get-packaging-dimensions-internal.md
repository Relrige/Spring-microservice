---
status: DONE
service: inventory-service
---
# EP-INV-05: Get Packaging Dimensions (Internal)

## Context
Delivery Service reads gross weight and carton dimensions during automatic TTN generation. A product without packaging data makes the delivery `TTN_FAILED`, so the two failure cases must be clearly distinguishable.
*References:* [Inventory Spec — EP-INV-05](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Technical Insights §2–3, §5](../../design/user-stories/inventory-worker/inventory-worker-technical-insights.md), [Delivery Service Spec](../../design/services-specs/delivery-service-spec.md)

## Acceptance Criteria
- [x] `GET /inventory/stock/{productId}/packaging` returns `200 OK { grossWeightGrams, lengthCm, widthCm, heightCm }` when packaging is complete.
- [x] No `StockItem` → `404`.
- [x] `StockItem` exists but packaging is null or incomplete → `422 { reason: "packaging_data_missing" }`.
- [x] Non-UUID `productId` → `400`.
- [x] Route rule `GET /inventory/stock/{productId}/packaging` → `hasRole("SERVICE")`; anonymous `401`, any user role `403`, valid internal token succeeds. The `PUT` on the same path keeps its worker rule from [task 09](09-set-packaging-parameters.md).
- [x] Read-only: no `StockItem` creation, no events.
- [x] Tests: service tests for found / not found / incomplete; controller tests including the security cases and a test proving `GET` and `PUT` on the same path are authorized independently.

## Technical Notes / Constraints
- Readiness ([task 10](10-check-stock-readiness-internal.md)) and this endpoint share one private check in `StockService` (`getWithConfiguredPackaging`), so a missing `StockItem` is `404` and incomplete packaging is `422 packaging_data_missing` on both. Each endpoint has its own controller and integration tests that pin these outcomes.
- The response uses the spec field names (`grossWeightGrams`, …). [api-contracts.md](../../design/services-requirements/api-contracts.md) already uses the same names, so it needed no correction.
- Route coverage: `RouteSecurityRulesTest` also probes with the internal-service caller, so this internal-only route needs no exclusion.

## Deviations from spec / task
Same contract as agreed for [task 10](10-check-stock-readiness-internal.md) and [task 03](03-error-handling-and-validation.md):
- The `422` reason is `packaging_data_missing` (spec: `packaging_dimensions_missing`), because the check covers gross weight as well as dimensions. The Delivery Service tasks should expect this reason.
- The original note expected readiness to return `422` for a missing `StockItem` and this endpoint `404`. Readiness now also returns `404`, so both endpoints behave the same.

## Implementation
- `StockService.getPackaging(productId)` (`@Transactional(readOnly = true)`) → `StockItemEntity`; `checkReadiness` now reuses the same private check.
- `PackagingResponse` record and `StockController` `GET /{productId}/packaging`.
- `SecurityConfig`: `GET /inventory/stock/{productId}/packaging` → `hasRole("SERVICE")`.
- Tests: `StockServiceGetPackagingTest`, `StockGetPackagingControllerTest`, `GetPackagingIntegrationTest`.
