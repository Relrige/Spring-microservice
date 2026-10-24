---
status: DONE
service: inventory-service
---
# EP-INV-08: Worker Reads One Stock Item with Its Packaging

## Context
The worker's packaging form replaces all four values at once (EP-INV-03), but a worker cannot read the stored values: EP-INV-05 is internal (role `SERVICE`) and answers `422` exactly for the products whose packaging is incomplete. The form therefore always starts empty (frontend backlog [BL-03](../../../frontend/docs/backlog/BL-03-worker-reads-packaging.md)). This task adds a worker-facing read of one stock item, including the packaging values, so the form can be pre-filled.

*References:* [Inventory spec — EP-INV-01, EP-INV-03, EP-INV-05](../../design/services-specs/inventory-service-spec.md), [API Contracts — Inventory](../../design/services-requirements/api-contracts.md#4-inventory-service), [API Gateway task 13](../api-getaway/13-worker-stock-item-route.md) (the route)

## Acceptance Criteria
- [x] `GET /inventory/stock/{productId}` for role `INVENTORY_WORKER` answers `200` with the stock row of EP-INV-01 (`productId`, `title`, `productStatus`, `availableQuantity`, `reservedQuantity`, `totalQuantity`, `packagingConfigured`) plus `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`; each packaging value is `null` while it is not set, and the answer is `200` (not `422`) when packaging is incomplete.
- [x] No stock item for the product → `404`; the read creates nothing and calls no other service.
- [x] Other roles → `403`; an internal service call → `403`. EP-INV-05 keeps its internal contract unchanged.
- [x] `openapi/public.json` regenerated.
- [x] Inventory spec and `api-contracts.md` updated (spec sync).

## Decisions (2026-10-23)
1. Option A of BL-03, chosen by the user: a new worker read, routed by the gateway; EP-INV-05 stays internal with its `422` contract for Delivery and Catalog.
2. The gateway route is a separate task in the gateway's folder ([task 13](../api-getaway/13-worker-stock-item-route.md)), done in the same run because the user asked for the whole feature.

## Technical Notes / Constraints
- The path `GET /inventory/stock/{productId}` does not clash with `GET /inventory/stock` (list) or the sub-paths `/adjust`, `/packaging`, `/readiness`.

## Implementation Notes (2026-10-23)
- **Endpoint:** `StockController.getStockItem` maps `GET /inventory/stock/{productId}` and answers `StockItemResponse`: the fields of `StockBalanceResponse` plus `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm` as nullable `Integer`s (absent from the JSON while not set, as the other optional fields).
- **Service:** `StockService.getStockItem` reads the row (`readOnly` transaction) and throws the existing `StockItemNotFoundException` (→ `404`); it does not call Catalog and creates nothing, unlike EP-INV-02/03.
- **Security:** `SecurityConfig` gets `GET /inventory/stock/{productId}` → `INVENTORY_WORKER`; internal callers have role `SERVICE` and are refused like other non-workers.
- **OpenAPI:** `openapi/public.json` regenerated (one operation, one schema); `internal.json` unchanged.
- **Verified:**
  - `GetStockItemIntegrationTest`: configured packaging returns the row and the stored values; a stock item without packaging returns `200` with the four values absent; an unknown product is `404` and no row is created; catalog manager, customer and an internal service call get `403`; no identity gets `401`.
  - `RouteSecurityRulesTest` finds the new route reachable (explicit rule); `GetPackagingIntegrationTest` (EP-INV-05) unchanged and green.
  - Full suite `mvnw.cmd clean test` (JDK 25): 45 test classes, 395 tests, all green.

## Deviations from spec / task
- **Packaging values are absent rather than `null` in the JSON** when unset, because the service serializes like the other optional fields (`title`, `productStatus`). Clients treat missing and `null` alike (frontend rule "optional means missing or null").
- **The `404` for a missing stock item** is the same as EP-INV-05's; the worker reaches this read only from a row of the stock list, where the item exists.

*Spec sync (2026-10-23):* EP-INV-08 is now part of the Inventory spec (new section) and of `api-contracts.md` (Inventory table).
