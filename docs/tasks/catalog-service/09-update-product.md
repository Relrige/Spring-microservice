---
status: DONE
service: catalog-service
---
# EP-CAT-02: Update Product Information

## Context
A Catalog Manager edits the descriptive data and price of an existing product. A price change affects only future orders: Order Service keeps its own `OrderItemSnapshot` prices, so Catalog does not need any history for this.
*References:* [Catalog Spec — EP-CAT-02](../../design/services-specs/catalog-service-spec.md), [Catalog Manager Scenarios (SCN-CM04)](../../design/scenarios/catalog-manager-scenarios.md)

## Acceptance Criteria
- [x] `PUT /catalog/products/{id}` accepts `{ title, description, categoryId, basePrice }` and returns `200 OK` with the updated product.
- [x] Product `id` not found → `404`.
- [x] Body validation as in EP-CAT-01 → `400` with field errors.
- [x] Target `categoryId` not found → `422`.
- [x] `status` and `stockStatus` are **not** changed by this endpoint, even if the client sends them.
- [x] It works for both `ACTIVE` and `NOT_ACTIVE` products.
- [x] Tests: service unit tests and controller/integration tests for each edge case, including "stockStatus written by an event is not overwritten by an update".

## Technical Notes / Constraints
- `PUT` replaces the editable fields: all four are required. Partial updates (the draft's "keep the old value if null") are not in the spec; do not carry that behaviour over.
- The response DTO shape is not defined in detail in the spec ("the updated product"). Decide it once (suggested: `productId, title, description, categoryId, basePrice, status, stockStatus`, the manager view) and reuse the same DTO where it fits in [task 16](16-browse-and-filter-products.md).
- Order of checks follows the spec: `404` first, then validation, then the category check. Pin it with a test.
- Concurrent edit by two managers (last write wins vs. optimistic locking) is a design question tied to the `@Version` decision from [task 03](03-product-entity-and-persistence.md); apply that decision here.
- This endpoint must not touch `stockStatus` through a stale read-modify-write; update only the editable columns.
- Authorization is added in [task 12](12-trusted-header-security.md); until then the endpoint is open (local use only).
- Products cannot be read back individually yet: the customer view ([task 15](15-get-product-details.md)) shows only `ACTIVE` products, and activation arrives in [task 14](14-change-product-status.md). Tests verify results through the repository or the `PUT` response.
- **Response DTO (decision):** `ProductResponse { productId, title, description, categoryId, basePrice, status, stockStatus }`, the manager view. Reuse it in [task 16](16-browse-and-filter-products.md) where it fits.
- **Check order (implemented):** `404` → body validation via `RequestValidator` (`400`) → category check (`422`), pinned by integration tests. Concurrent edits by two managers are last-write-wins (see the `@Version` decision in [task 03](03-product-entity-and-persistence.md)). Thanks to `@DynamicUpdate`, the update never writes `status` or `stockStatus`.
