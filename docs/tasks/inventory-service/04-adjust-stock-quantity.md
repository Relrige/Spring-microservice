---
status: DONE
service: inventory-service
---
# EP-INV-02: Adjust Stock Quantity (Restock / Write-off) — Early Version

## Context
An Inventory Worker restocks or writes off a product with a signed `delta`. This is also the endpoint that changes quantities in the simplest way, so it is the source of the first stock events ([task 06](06-stock-events-publish-and-listen.md)).

According to the spec, when no `StockItem` exists for the product the service verifies the product with Catalog and then creates the `StockItem`. **This early version makes no inter-service call.** A missing `StockItem` is created on demand, without checking that the product exists in Catalog. That is a deliberate, temporary deviation from the spec (any UUID can get a `StockItem`); the Catalog check is added in [task 19](19-catalog-product-existence-client.md). Record the deviation in the PR.

Event publishing is also **not** part of this task; [task 06](06-stock-events-publish-and-listen.md) adds it.
*References:* [Inventory Spec — EP-INV-02](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Scenarios (SCN-INV01)](../../design/scenarios/inventory-worker-scenarios.md), [StockItem persistence](02-stock-item-entity-and-persistence.md)

## Acceptance Criteria
- [x] `POST /inventory/stock/{productId}/adjust` accepts `{ delta }` and returns `200 OK { newAvailableQuantity }`.
- [x] `delta` must be present, an integer, and non-zero → otherwise `400` ("Delta must be non-zero"). Non-UUID `productId` → `400`.
- [x] Missing `StockItem` → created with `availableQuantity = 0`, `reservedQuantity = 0`, no packaging (temporary behaviour, see Context), then the delta is applied.
- [x] `availableQuantity + delta < 0` → `422` ("Insufficient available stock for write-off"); nothing is changed.
- [x] Restocking works for any product regardless of Catalog status (the service has no notion of status).
- [x] The adjustment is safe under concurrent requests (two simultaneous write-offs cannot take the quantity below zero), using the concurrency approach decided in [task 02](02-stock-item-entity-and-persistence.md).
- [x] Two concurrent first-time adjustments for a product with no `StockItem` result in one `StockItem` and both deltas applied (not a duplicate-key `500`).
- [x] The temporary-deviation comment in code points at task 19, and a test named so it is obvious which one changes when the Catalog check arrives.
- [x] Tests: service unit tests (restock, write-off, below zero, create-on-demand) and controller/integration tests for each edge case in the spec.

## Technical Notes / Constraints
- Structure the service method so a later task can add "decide whether a threshold was crossed and record an event" without restructuring: compute `before` and `after` quantity inside the same locked transaction.
- A recommended-but-TBD `reason` string is in the spec; leave it out for now unless the team decides otherwise.
- Route authorization (`INVENTORY_WORKER`) is added in [task 07](07-trusted-header-security.md); until then the endpoint is open (local use only).

## Implementation Notes
- Missing `StockItem` is created with `StockItemRepository.insertIfAbsent` (native `INSERT … ON CONFLICT (product_id) DO NOTHING`) instead of `save()`, so concurrent first-time adjustments never fail with a duplicate key. The row is then locked with `findByIdForUpdate`. A rejected write-off rolls the transaction back, so no row is left for an unknown product.
- `delta` is validated by `@NotNull` plus a custom `@NonZero` constraint (supports `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`; not `BigDecimal`/`BigInteger`).
- Temporary deviation (no Catalog existence check) is marked in `StockService` and in the test `adjustStock_missingStockItem_isCreatedOnDemandWithoutCatalogCheck_changesInTask19`; both change in [task 19](19-catalog-product-existence-client.md).
