---
status: DONE
service: inventory-service
---
# EP-INV-01: View Stock Balances

## Context
The Inventory Worker sees all stock items with computed totals and whether packaging is configured. The spec leaves the filter parameters open ("TBD during implementation"), so this task also decides them.
*References:* [Inventory Spec — EP-INV-01](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Scenarios (SCN-INV03)](../../design/scenarios/inventory-worker-scenarios.md), [API Contracts — Inventory](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] `GET /inventory/stock` returns `200 OK [ { productId, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured } ]`; no stock items → `200 []`.
- [x] `totalQuantity` and `packagingConfigured` use the derivations from [task 02](02-stock-item-entity-and-persistence.md) (not recomputed in the controller).
- [x] Optional filter `packagingMissing=true` returns only items where packaging is not fully configured.
- [x] Optional filter `lowStock=true` returns items whose `availableQuantity` is at or below a configurable threshold (`inventory.low-stock-threshold`, default chosen and documented).
- [x] Filters combine with AND; invalid boolean values → `400`.
- [x] Results have a stable order (by `productId`) so the list does not jump between requests.
- [x] Route rule `GET /inventory/stock` → `hasRole("INVENTORY_WORKER")`; `401`/`403` tests.
- [x] Documentation: record the final filter names in the Inventory spec (replacing "TBD") and in [api-contracts.md](../../design/services-requirements/api-contracts.md).
- [x] Tests: repository/service tests for each filter and the combination; controller tests for the response shape and empty result.

## Technical Notes / Constraints
- The spec returns a plain array without paging. Warehouse catalogs can be large; decide whether to follow the spec as written (acceptable for the study project) or add optional `page`/`size`. If paging is added it must be backwards compatible with the array in the spec, or the spec updated; record the decision.
- Do filtering in the database (derived/JPQL/`Specification`), not by loading everything and filtering in memory.
- The packaging-missing condition in SQL must match `packagingConfigured` in Java exactly (null **or** ≤ 0); cover both with the same test data.
- Product titles are not available here (Catalog owns them); the UI joins on `productId`.
- **Decisions (paging, filtering, sorting):**
  - Response is the shared envelope `{ content, page, size, totalElements }` (`page` zero-based) instead of a plain array; `page` default 0, `size` default 20, max 100.
  - `sortBy=field` or `sortBy=field,asc|desc` (case-insensitive direction), whitelist `productId`, `availableQuantity`, `reservedQuantity`; `productId` is always the final tiebreaker and the default order. Unknown field/direction → `400` with `errors.sortBy`.
  - Only one filter: `packagingConfigured=true|false` (omitted = no filter). It replaces `packagingMissing`; `lowStock` and `inventory.low-stock-threshold` are dropped.
  - Filtering uses a JPA `Specification` (`StockItemSpecifications`); no generated database columns (the earlier V2 migration was dropped). `totalQuantity` is computed in Java and is not sortable.
- **Reusability (for Catalog tasks 13/16 and other list endpoints):**
  - `PageResponse<T>` (`paging` package) is a generic record `{ content, page, size, totalElements }`; `PageResponse.from(Page, mapper)` converts a Spring Data page. It never serializes Spring's `Page` directly. The services are independent deployables, so each service keeps its own copy of the class, and the shared thing is the JSON contract.
  - `PageableParser` takes a whitelist (public name → entity property) and a tiebreaker property, and turns `page`/`size`/`sortBy` into a `Pageable`. Another endpoint only supplies its own whitelist; defaults (`DEFAULT_SIZE` 20, `MAX_SIZE` 100) and the `field[,asc|desc]` format stay the same.
  - Invalid input throws `InvalidQueryParameterException`, rendered as the standard `400` validation problem with `errors.<parameter>`.
  - Filters are small composable `Specification`s (`StockItemSpecifications`); the repository extends `JpaSpecificationExecutor`, and the service combines only the filters that were supplied with AND. A new filter is one new method plus one `if`.
  - Bind `sortBy` as a plain `String` (not `List<String>`), because Spring splits comma-separated values for collection types.
  - Known limits: `totalQuantity` is computed in Java and not sortable or filterable; adding that would need either a custom `ORDER BY` expression in a Specification or a database-computed column.
- **Docs still to update at sprint end:** Inventory spec (EP-INV-01) and `api-contracts.md` should replace the plain array and the "TBD" filters with the envelope, `packagingConfigured`, `page`/`size`/`sortBy`.
