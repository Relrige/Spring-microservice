---
status: DONE
service: delivery-service
---
# EP-DEL-01: Batch Get Shipment Statuses (Internal)

## Context
Order Service shows delivery state in the fulfillment queue and the order details, and checks it before dispatch. Delivery state is never copied into Order; Order asks Delivery in **one** batch call per screen.
*References:* [Delivery Spec — EP-DEL-01](../../design/services-specs/delivery-service-spec.md), [Order Spec — EP-ORD-05, EP-ORD-07, EP-ORD-08](../../design/services-specs/order-service-spec.md), [API Contracts — Delivery](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] `GET /delivery/shipments?orderIds=uuid1,uuid2,...` (role `SERVICE`).
- [x] 1–100 UUIDs; duplicates collapsed; empty, too many or malformed → `400` with `errors.orderIds`.
- [x] One database query for all IDs.
- [x] `200 OK [ { orderId, status, ttn, failureReason } ]`, one entry per distinct requested `orderId`, in request order; `status = null` (and null fields) when no shipment exists.
- [x] A `CANCELLED` tombstone is returned as `CANCELLED`; all six statuses are passed through unchanged (including `IN_TRANSIT`).
- [x] Tests: mixed found and missing IDs; order of the response; duplicates; each `400` case; `401`/`403` (from task 04).

## Technical Notes / Constraints
- Response DTO, never the entity.
- The parameter is comma-separated in one value, as in Catalog's `batch-get`; reuse its parsing approach.

## Implementation Notes (2026-10-15)
- **`controllers/ShipmentController`** (`@RequestMapping("/delivery")`): `GET /shipments` binds `@RequestParam(required = false) List<UUID> orderIds` from one comma-separated value, as Catalog's batch-get does. A non-UUID element fails conversion → `400` with `errors.orderIds` (task 03's type-mismatch handler).
- **`ShipmentService.getStatuses`** (read-only transaction): missing, empty or an empty element (`a,,b`) → `InvalidQueryParameterException` (`400`, `errors.orderIds`); duplicates are collapsed (`LinkedHashSet`, keeps request order); more than 100 **distinct** values → `400`. One `findAllByOrderIdIn` query, then one entry per distinct ID in request order; no shipment → `ShipmentStatusResponse.notFound` (`status`, `ttn`, `failureReason` null).
- **DTO** `dto/ShipmentStatusResponse(orderId, status, ttn, failureReason)`; the six statuses are passed through as they are, the tombstone as `CANCELLED`.
- **`RouteSecurityRulesTest`** (copied from Inventory, deferred from task 04) now checks that every route of the real controllers has a rule.
- **Verified (2026-10-15):** `BatchGetShipmentsIntegrationTest` (Testcontainers, TTN job off): mixed found/missing in request order with a duplicate collapsed, `IN_TRANSIT`, `TTN_FAILED` with reason, tombstone `CANCELLED` with `ttn = null`; all missing → null statuses; 100 accepted, 101 → `400`; missing parameter, empty value, empty element, non-UUID → `400` with `errors.orderIds`; anonymous `401`, worker `403`. `RouteSecurityRulesTest` passes.
