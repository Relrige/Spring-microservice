---
status: TODO
service: delivery-service
---
# Error Handling and Validation

## Context
All services answer errors with the same RFC 9457 `ProblemDetail` shape. Delivery adds one status the others do not use: `424 Failed Dependency` for a manual TTN retry that the carrier or Inventory rejected, with a stable `reason` the fulfillment queue can show.
*References:* [Delivery Spec — Responses and errors](../../design/services-specs/delivery-service-spec.md), [Inventory error handling task](../inventory-service/03-error-handling-and-validation.md)

## Acceptance Criteria
- [ ] Copy the global exception handler and `ProblemDetail` factory from `inventory-service` (fields `type`, `title`, `status`, `detail`, `timestamp`, `service`), with `service = delivery-service`.
- [ ] Typed exceptions: `ShipmentNotFoundException` → `404`; `InvalidShipmentStateException` (from the entity's transition methods) → `409`; `TtnGenerationFailedException` → `424` with type `ttn-generation-failed` and property `reason`.
- [ ] `400` with `errors` for: invalid JSON, broken field rules, a non-UUID path variable, and an invalid `orderIds` query value (empty, more than 100 values, a non-UUID element).
- [ ] Unexpected exceptions → `500` with a generic message; the stack trace is only logged.
- [ ] Tests (WebMvc slice with a test controller or the real controllers once they exist): each mapping returns the documented status, `type` and extra property.

## Technical Notes / Constraints
- Keep the copied handler close to Inventory's version so later fixes can be applied to every copy.
- `reason` values are a contract with the fulfillment queue. Use `packaging_data_missing:{productId}`, `stock_item_not_found:{productId}`, `inventory_unavailable`, `carrier_error: <message>`.
