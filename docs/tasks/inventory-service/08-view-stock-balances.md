---
status: TODO
service: inventory-service
---
# EP-INV-01: View Stock Balances

## Context
The Inventory Worker sees all stock items with computed totals and whether packaging is configured. The spec leaves the filter parameters open ("TBD during implementation"), so this task also decides them.
*References:* [Inventory Spec — EP-INV-01](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Scenarios (SCN-INV03)](../../design/scenarios/inventory-worker-scenarios.md), [API Contracts — Inventory](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] `GET /inventory/stock` returns `200 OK [ { productId, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured } ]`; no stock items → `200 []`.
- [ ] `totalQuantity` and `packagingConfigured` use the derivations from [task 02](02-stock-item-entity-and-persistence.md) (not recomputed in the controller).
- [ ] Optional filter `packagingMissing=true` returns only items where packaging is not fully configured.
- [ ] Optional filter `lowStock=true` returns items whose `availableQuantity` is at or below a configurable threshold (`inventory.low-stock-threshold`, default chosen and documented).
- [ ] Filters combine with AND; invalid boolean values → `400`.
- [ ] Results have a stable order (by `productId`) so the list does not jump between requests.
- [ ] Route rule `GET /inventory/stock` → `hasRole("INVENTORY_WORKER")`; `401`/`403` tests.
- [ ] Documentation: record the final filter names in the Inventory spec (replacing "TBD") and in [api-contracts.md](../../design/services-requirements/api-contracts.md).
- [ ] Tests: repository/service tests for each filter and the combination; controller tests for the response shape and empty result.

## Technical Notes / Constraints
- The spec returns a plain array without paging. Warehouse catalogs can be large; decide whether to follow the spec as written (acceptable for the study project) or add optional `page`/`size`. If paging is added it must be backwards compatible with the array in the spec, or the spec updated; record the decision.
- Do filtering in the database (derived/JPQL/`Specification`), not by loading everything and filtering in memory.
- The packaging-missing condition in SQL must match `packagingConfigured` in Java exactly (null **or** ≤ 0); cover both with the same test data.
- Product titles are not available here (Catalog owns them); the UI joins on `productId`.
