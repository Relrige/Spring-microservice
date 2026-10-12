---
status: DONE
service: inventory-service
---
# EP-INV-03: Set / Update Packaging Parameters — Early Version

## Context
The Inventory Worker records gross weight and carton dimensions of a product. Setting them is a prerequisite for product activation in Catalog and for TTN generation in Delivery.

As in [task 04](04-adjust-stock-quantity.md), the spec requires a Catalog existence check when no `StockItem` exists. **This early version makes no inter-service call:** a missing `StockItem` is created on demand without verifying the product. This temporary deviation is replaced in [task 19](19-catalog-product-existence-client.md).
*References:* [Inventory Spec — EP-INV-03](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Scenarios (SCN-INV02)](../../design/scenarios/inventory-worker-scenarios.md)

## Acceptance Criteria
- [x] `PUT /inventory/stock/{productId}/packaging` accepts `{ grossWeightGrams, lengthCm, widthCm, heightCm }` and returns `200 OK`.
- [x] All four fields are required positive integers → otherwise `400` with field errors (any value ≤ 0, missing, or non-integer).
- [x] The call replaces all four values at once; it is idempotent (repeating it with the same body has the same result).
- [x] Missing `StockItem` → created on demand with zero quantities and then updated (temporary behaviour, see Context); two concurrent first-time requests produce one `StockItem`.
- [x] Updating packaging does not change quantities and emits no event.
- [x] Route rule `PUT /inventory/stock/{productId}/packaging` → `hasRole("INVENTORY_WORKER")`; `401`/`403` tests.
- [x] The temporary-deviation comment in code points at task 19.
- [x] Tests: service unit tests and controller/integration tests for each edge case in the spec, plus the shared create-on-demand path with [task 04](04-adjust-stock-quantity.md).

## Technical Notes / Constraints
- Extract "get or create `StockItem`" into one method used by adjust and packaging, so the Catalog check in task 19 is added in exactly one place.
- Maximum values (for example weight in grams) should be bounded to reject typos; choose limits and document them.
- The same path with `GET` is a different, internal endpoint ([task 11](11-get-packaging-dimensions-internal.md)); keep routes and security rules separate by method.
- **Decisions:**
  - Success returns `204 No Content` with an empty body (not `200 OK` as in the first spec version; the spec and `api-contracts.md` were updated at sprint end, 2026-10-12).
  - No upper bounds on the four values (only `> 0`); the "choose limits" note is intentionally not applied.
  - "Get or create" is the private `StockService.getOrCreateForUpdate` (insert-if-absent, then row lock), used by `adjustStock` and `setPackaging`. The temporary Catalog-check deviation comment lives there and points at task 19, so the check is added in one place. Packaging is written on the locked row, so a concurrent adjust cannot be lost.
  - Non-integer values such as `1.5` were silently truncated by Jackson; `spring.jackson.deserialization.accept-float-as-int: false` now makes them `400` for every endpoint (this also covers `delta` in the adjust endpoint).
