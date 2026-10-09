---
status: TODO
service: inventory-service
---
# EP-INV-05: Get Packaging Dimensions (Internal)

## Context
Delivery Service reads gross weight and carton dimensions during automatic TTN generation. A product without packaging data makes the delivery `TTN_FAILED`, so the two failure cases must be clearly distinguishable.
*References:* [Inventory Spec — EP-INV-05](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Technical Insights §2–3, §5](../../design/user-stories/inventory-worker/inventory-worker-technical-insights.md), [Delivery Service Spec](../../design/services-specs/delivery-service-spec.md)

## Acceptance Criteria
- [ ] `GET /inventory/stock/{productId}/packaging` returns `200 OK { grossWeightGrams, lengthCm, widthCm, heightCm }` when packaging is complete.
- [ ] No `StockItem` → `404`.
- [ ] `StockItem` exists but packaging is null or incomplete → `422 { reason: "packaging_dimensions_missing" }`.
- [ ] Non-UUID `productId` → `400`.
- [ ] Route rule `GET /inventory/stock/{productId}/packaging` → `hasRole("SERVICE")`; anonymous `401`, any user role `403`, valid internal token succeeds. The `PUT` on the same path keeps its worker rule from [task 09](09-set-packaging-parameters.md).
- [ ] Read-only: no `StockItem` creation, no events.
- [ ] Tests: service tests for found / not found / incomplete; controller tests including the security cases and a test proving `GET` and `PUT` on the same path are authorized independently.

## Technical Notes / Constraints
- Note the difference from [task 10](10-check-stock-readiness-internal.md): a missing `StockItem` is `404` here but `422` there. This follows the spec (Catalog wants a business reason; Delivery treats a missing item as a not-found error). Pin both with tests so they cannot drift together by accident.
- The response uses the field names of the spec (`grossWeightGrams`, …); the API contract table mentions `totalWeightGrams`, which Delivery computes from quantities. Use the spec names and correct [api-contracts.md](../../design/services-requirements/api-contracts.md) if it disagrees.
- Exclude this route from the `ADMIN` route-coverage test (internal-only).
