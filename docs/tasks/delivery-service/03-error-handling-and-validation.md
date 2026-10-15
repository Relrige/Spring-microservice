---
status: DONE
service: delivery-service
---
# Error Handling and Validation

## Context
All services answer errors with the same RFC 9457 `ProblemDetail` shape. Delivery adds one status the others do not use: `424 Failed Dependency` for a manual TTN retry that the carrier or Inventory rejected, with a stable `reason` the fulfillment queue can show.
*References:* [Delivery Spec — Responses and errors](../../design/services-specs/delivery-service-spec.md), [Inventory error handling task](../inventory-service/03-error-handling-and-validation.md)

## Acceptance Criteria
- [x] Copy the global exception handler and `ProblemDetail` factory from `inventory-service` (fields `type`, `title`, `status`, `detail`, `timestamp`, `service`), with `service = delivery-service`.
- [x] Typed exceptions: `ShipmentNotFoundException` → `404`; `InvalidShipmentStateException` (from the entity's transition methods) → `409`; `TtnGenerationFailedException` → `424` with type `ttn-generation-failed` and property `reason`.
- [x] `400` with `errors` for: invalid JSON, broken field rules, a non-UUID path variable, and an invalid `orderIds` query value (empty, more than 100 values, a non-UUID element).
- [x] Unexpected exceptions → `500` with a generic message; the stack trace is only logged.
- [x] Tests (WebMvc slice with a test controller or the real controllers once they exist): each mapping returns the documented status, `type` and extra property.

## Technical Notes / Constraints
- Keep the copied handler close to Inventory's version so later fixes can be applied to every copy.
- `reason` values are a contract with the fulfillment queue. Use `packaging_data_missing:{productId}`, `stock_item_not_found:{productId}`, `inventory_unavailable`, `carrier_error: <message>`.

## Implementation Notes (2026-10-15)
- **`controllers/GlobalExceptionHandler`** is a copy of Inventory's (same `problem(...)` factory, `type`/`title`/`status`/`detail`/`timestamp`/`service`, `service = delivery-service`), with Delivery's exceptions instead of Inventory's.
- **Mappings:** `ShipmentNotFoundException` → `404 not-found`; `InvalidShipmentStateException` (thrown by the entity's transition methods) → `409 conflict`; `TtnGenerationFailedException` → `424 ttn-generation-failed` with property `reason`; `InvalidQueryParameterException` (copied from Inventory's `paging` package into `exceptions`) → `400` with `errors.{parameter}`; anything else → `500 internal-error` with a generic text, stack trace only in the log.
- **Additions over Inventory's copy:** `handleTypeMismatch` (non-UUID path or query value) and `handleHttpMessageNotReadable` (invalid JSON) also add `errors` (`{name: ["must be a valid UUID"]}`, `{body: ["malformed JSON"]}`), as this task requires. Inventory answers these with `400` but without `errors`.
- The `reason` values (`packaging_data_missing:{productId}`, `stock_item_not_found:{productId}`, `inventory_unavailable`, `carrier_error: <message>`) are produced by the Inventory client (task 09) and the carrier adapter (task 11).
- **Verified (2026-10-15):** `GlobalExceptionHandlerTest` (WebMvc slice with a probe controller, 8 cases) passes.
