---
status: TODO
service: order-service
---
# EP-ORD-01: Checkout Soft Check

## Context
When a logged-in customer clicks "Proceed to Checkout", Order checks whether every product in the cart can be ordered: it must be `ACTIVE` and in stock. Nothing is stored or reserved; this only spares the customer a failed submission. Since decision D2 both answers come from one Catalog call (`status` and `stockStatus`).
*References:* [Order Spec — EP-ORD-01, Request limits](../../design/services-specs/order-service-spec.md), [Customer Scenarios (SCN-C11, SCN-C12)](../../design/scenarios/customer-scenarios.md), [Catalog task 24](../catalog-service/24-stock-status-in-batch-get.md)

## Acceptance Criteria
- [ ] `POST /orders/validate` with `{ items: [ { productId, quantity } ] }`; role `CUSTOMER`.
- [ ] Validation → `400`: 1–100 items, UUID `productId`, `quantity ≥ 1`, no duplicate `productId` (a custom constraint like Inventory's `@UniqueProductIds`).
- [ ] One Catalog batch-get call. Missing or `NOT_ACTIVE` → reason `NOT_ACTIVE`; `OUT_OF_STOCK` → reason `INSUFFICIENT_STOCK` (a product that is both is reported once, as `NOT_ACTIVE`).
- [ ] Any ineligible item → `422 { ineligibleItems: [ { productId, reason } ] }` listing **all** of them; otherwise `204`.
- [ ] Catalog unavailable → `503`; never `204` when the check could not run.
- [ ] Tests: service unit tests (all fine, inactive, missing, out of stock, mixed); controller slice tests (validation, `422` body, `503`); a WireMock integration test.

## Technical Notes / Constraints
- `stockStatus` has no quantities and is eventually consistent: "2 left, 5 requested" passes here and is rejected by the reservation in [task 08](08-submit-order.md). Say so in the response documentation, not in an error.
- No Inventory call here (D2).
