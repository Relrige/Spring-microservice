---
status: TODO
service: inventory-service
---
# EP-INV-03: Set / Update Packaging Parameters — Early Version

## Context
The Inventory Worker records gross weight and carton dimensions of a product. Setting them is a prerequisite for product activation in Catalog and for TTN generation in Delivery.

As in [task 04](04-adjust-stock-quantity.md), the spec requires a Catalog existence check when no `StockItem` exists. **This early version makes no inter-service call:** a missing `StockItem` is created on demand without verifying the product. This temporary deviation is replaced in [task 19](19-catalog-product-existence-client.md).
*References:* [Inventory Spec — EP-INV-03](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Scenarios (SCN-INV02)](../../design/scenarios/inventory-worker-scenarios.md)

## Acceptance Criteria
- [ ] `PUT /inventory/stock/{productId}/packaging` accepts `{ grossWeightGrams, lengthCm, widthCm, heightCm }` and returns `200 OK`.
- [ ] All four fields are required positive integers → otherwise `400` with field errors (any value ≤ 0, missing, or non-integer).
- [ ] The call replaces all four values at once; it is idempotent (repeating it with the same body has the same result).
- [ ] Missing `StockItem` → created on demand with zero quantities and then updated (temporary behaviour, see Context); two concurrent first-time requests produce one `StockItem`.
- [ ] Updating packaging does not change quantities and emits no event.
- [ ] Route rule `PUT /inventory/stock/{productId}/packaging` → `hasRole("INVENTORY_WORKER")`; `401`/`403` tests.
- [ ] The temporary-deviation comment in code points at task 19.
- [ ] Tests: service unit tests and controller/integration tests for each edge case in the spec, plus the shared create-on-demand path with [task 04](04-adjust-stock-quantity.md).

## Technical Notes / Constraints
- Extract "get or create `StockItem`" into one method used by adjust and packaging, so the Catalog check in task 19 is added in exactly one place.
- Maximum values (for example weight in grams) should be bounded to reject typos; choose limits and document them.
- The same path with `GET` is a different, internal endpoint ([task 11](11-get-packaging-dimensions-internal.md)); keep routes and security rules separate by method.
