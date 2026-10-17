---
status: DONE
service: catalog-service
---
# Stock Status in the Internal Batch-Get

## Context
Order's checkout soft check (EP-ORD-01) must tell the customer whether every product is active **and** in stock. Catalog's internal batch-get returns `{ productId, title, basePrice, status }`; it already stores `stockStatus` (`IN_STOCK` / `OUT_OF_STOCK`, kept up to date from Inventory's stock events, [task 18](18-consume-stock-events.md)). Adding that field lets Order answer with one call and no new Inventory endpoint (decision D2 in the [Order spec](../../design/services-specs/order-service-spec.md)).
*References:* [Catalog Spec — EP-CAT-09](../../design/services-specs/catalog-service-spec.md), [Batch-get task](17-batch-get-products-internal.md), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] `BatchProductResponse` gains `stockStatus`; `GET /catalog/products/batch-get` returns `[ { productId, title, basePrice, status, stockStatus } ]`.
- [x] The value comes from the stored column; no call to Inventory is added.
- [x] Existing callers keep working: Inventory's existence check reads only `productId` and ignores unknown fields (tolerant reader); verify with its client test or a contract note.
- [x] Tests: the field is present for `IN_STOCK` and `OUT_OF_STOCK` products, also for `NOT_ACTIVE` ones.
- [x] The spec and `api-contracts.md` already describe the field (updated 2026-10-16); check they match.

## Technical Notes / Constraints
- `stockStatus` is eventually consistent and has no quantities. Order uses it only for the soft check; the hard check is Inventory's reservation.
- Additive change only: do not rename or remove existing fields.

## Implementation (2026-10-17)
- `BatchProductResponse` has `stockStatus`, filled from the stored column in `from(Product)`; no Inventory call was added.
- Inventory's `CatalogProduct` is annotated `@JsonIgnoreProperties(ignoreUnknown = true)`, so its existence check is unaffected.
- Tests: `CatalogReadAndStatusIntegrationTest` checks the field for `IN_STOCK`/`OUT_OF_STOCK` and for a `NOT_ACTIVE` product; `ProductControllerTest` checks the JSON. All 259 Catalog tests pass. The spec and `api-contracts.md` match.
