---
status: DONE
service: inventory-service
---
# Consistent Error Handling

## Context
The spec uses `400`, `404`, `409`, `422` and (once Catalog is called) `503`. They should share one error format and a small set of domain exceptions instead of each controller shaping its own. The draft threw `RuntimeException`s, which surface as `500`.
*References:* [Inventory Spec](../../design/services-specs/inventory-service-spec.md), [Auth Service error handling task](../auth-service/05-error-handling-and-validation.md), [Catalog error handling task](../catalog-service/04-error-handling-and-validation.md)

## Acceptance Criteria
- [x] A `@RestControllerAdvice` (extending `ResponseEntityExceptionHandler`) maps validation failures to `400` with a field-level `errors` map, malformed JSON to `400`, and type mismatches (for example a non-UUID `{productId}`) to `400`.
- [x] Domain exceptions and mapping:
  - `StockItemNotFoundException` → `404` (EP-INV-05)
  - `InsufficientStockException` for write-off → `422` ("Insufficient available stock for write-off")
  - `InsufficientStockForReservationException` carrying `insufficientItems: [ { productId, requested, available } ]` → `422` (EP-INV-06)
  - `ReservationAlreadyExistsException` (order already has `ACTIVE` reservations) → `409`
  - readiness / packaging "not ready" results carry a `reason` property (`stock_item_not_found`, `packaging_dimensions_missing`) → `422`
  - (added later, [task 19](19-catalog-product-existence-client.md)) `ProductNotFoundInCatalogException` → `404`, `CatalogUnavailableException` → `503`
- [x] Unexpected exceptions return `500` with a generic message and no stack trace; the stack trace is logged server-side.
- [x] Error format is the RFC 9457 `ProblemDetail` shape used by the other services: `type`, `title`, `status`, `detail`, `timestamp`, `service`. Extra properties (`errors`, `reason`, `insufficientItems`) are added where listed above.
- [x] Tests cover each mapped exception through a controller slice test.

## Technical Notes / Constraints
- Reuse the exact response shape of `auth-service` and `catalog-service` so clients see one format across the platform.
- Keep `400` vs `422` consistent with the spec: `400` = malformed request or field rule violated (for example `delta == 0`); `422` = well-formed request that cannot be done in the current state (not enough stock).
- Exceptions may be added together with the task that first throws them, as long as the mapping lives in this one advice.
- `401`/`403` responses are rendered by the entry point and handler from [task 07](07-trusted-header-security.md); they must use the same body shape.

## Deviations from spec / task
- No generic "not ready" exception with a `reason` enum. Instead of `StockNotReadyException`, a dedicated `PackagingNotConfiguredException` (→ `422`) covers missing packaging data (gross weight **and** dimensions, not only dimensions).
- The packaging response property is `reason: "packaging_data_missing"` (spec says `packaging_dimensions_missing`).
- A missing stock item always uses `StockItemNotFoundException` → `404`, including on the readiness endpoint ([task 10](10-check-stock-readiness-internal.md)); the spec's `422 { reason: "stock_item_not_found" }` for readiness is dropped.
