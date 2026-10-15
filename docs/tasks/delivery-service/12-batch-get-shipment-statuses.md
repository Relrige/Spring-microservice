---
status: TODO
service: delivery-service
---
# EP-DEL-01: Batch Get Shipment Statuses (Internal)

## Context
Order Service shows delivery state in the fulfillment queue and the order details, and checks it before dispatch. Delivery state is never copied into Order; Order asks Delivery in **one** batch call per screen.
*References:* [Delivery Spec — EP-DEL-01](../../design/services-specs/delivery-service-spec.md), [Order Spec — EP-ORD-05, EP-ORD-07, EP-ORD-08](../../design/services-specs/order-service-spec.md), [API Contracts — Delivery](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] `GET /delivery/shipments?orderIds=uuid1,uuid2,...` (role `SERVICE`).
- [ ] 1–100 UUIDs; duplicates collapsed; empty, too many or malformed → `400` with `errors.orderIds`.
- [ ] One database query for all IDs.
- [ ] `200 OK [ { orderId, status, ttn, failureReason } ]`, one entry per distinct requested `orderId`, in request order; `status = null` (and null fields) when no shipment exists.
- [ ] A `CANCELLED` tombstone is returned as `CANCELLED`; all six statuses are passed through unchanged (including `IN_TRANSIT`).
- [ ] Tests: mixed found and missing IDs; order of the response; duplicates; each `400` case; `401`/`403` (from task 04).

## Technical Notes / Constraints
- Response DTO, never the entity.
- The parameter is comma-separated in one value, as in Catalog's `batch-get`; reuse its parsing approach.
