---
status: TODO
service: inventory-service
---
# EP-INV-02: Adjust Stock Quantity (Restock / Write-off) — Early Version

## Context
An Inventory Worker restocks or writes off a product with a signed `delta`. This is also the endpoint that changes quantities in the simplest way, so it is the source of the first stock events ([task 06](06-stock-events-publish-and-listen.md)).

According to the spec, when no `StockItem` exists for the product the service verifies the product with Catalog and then creates the `StockItem`. **This early version makes no inter-service call.** A missing `StockItem` is created on demand, without checking that the product exists in Catalog. That is a deliberate, temporary deviation from the spec (any UUID can get a `StockItem`); the Catalog check is added in [task 19](19-catalog-product-existence-client.md). Record the deviation in the PR.

Event publishing is also **not** part of this task; [task 06](06-stock-events-publish-and-listen.md) adds it.
*References:* [Inventory Spec — EP-INV-02](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Scenarios (SCN-INV01)](../../design/scenarios/inventory-worker-scenarios.md), [StockItem persistence](02-stock-item-entity-and-persistence.md)

## Acceptance Criteria
- [ ] `POST /inventory/stock/{productId}/adjust` accepts `{ delta }` and returns `200 OK { newAvailableQuantity }`.
- [ ] `delta` must be present, an integer, and non-zero → otherwise `400` ("Delta must be non-zero"). Non-UUID `productId` → `400`.
- [ ] Missing `StockItem` → created with `availableQuantity = 0`, `reservedQuantity = 0`, no packaging (temporary behaviour, see Context), then the delta is applied.
- [ ] `availableQuantity + delta < 0` → `422` ("Insufficient available stock for write-off"); nothing is changed.
- [ ] Restocking works for any product regardless of Catalog status (the service has no notion of status).
- [ ] The adjustment is safe under concurrent requests (two simultaneous write-offs cannot take the quantity below zero), using the concurrency approach decided in [task 02](02-stock-item-entity-and-persistence.md).
- [ ] Two concurrent first-time adjustments for a product with no `StockItem` result in one `StockItem` and both deltas applied (not a duplicate-key `500`).
- [ ] The temporary-deviation comment in code points at task 19, and a test named so it is obvious which one changes when the Catalog check arrives.
- [ ] Tests: service unit tests (restock, write-off, below zero, create-on-demand) and controller/integration tests for each edge case in the spec.

## Technical Notes / Constraints
- Structure the service method so a later task can add "decide whether a threshold was crossed and record an event" without restructuring: compute `before` and `after` quantity inside the same locked transaction.
- A recommended-but-TBD `reason` string is in the spec; leave it out for now unless the team decides otherwise.
- Route authorization (`INVENTORY_WORKER`) is added in [task 07](07-trusted-header-security.md); until then the endpoint is open (local use only).
