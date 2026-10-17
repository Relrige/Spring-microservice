---
status: DONE
service: order-service
---
# EP-ORD-01: Checkout Soft Check

## Context
When a logged-in customer clicks "Proceed to Checkout", Order checks whether every product in the cart can be ordered: it must be `ACTIVE` and in stock. Nothing is stored or reserved; this only spares the customer a failed submission. Since decision D2 both answers come from one Catalog call (`status` and `stockStatus`).
*References:* [Order Spec — EP-ORD-01, Request limits](../../design/services-specs/order-service-spec.md), [Customer Scenarios (SCN-C11, SCN-C12)](../../design/scenarios/customer-scenarios.md), [Catalog task 24](../catalog-service/24-stock-status-in-batch-get.md)

## Acceptance Criteria
- [x] `POST /orders/validate` with `{ items: [ { productId, quantity } ] }`; role `CUSTOMER`.
- [x] Validation → `400`: 1–100 items, UUID `productId`, `quantity ≥ 1`, no duplicate `productId` (a custom constraint like Inventory's `@UniqueProductIds`).
- [x] One Catalog batch-get call. Missing or `NOT_ACTIVE` → reason `NOT_ACTIVE`; `OUT_OF_STOCK` → reason `INSUFFICIENT_STOCK` (a product that is both is reported once, as `NOT_ACTIVE`).
- [x] Any ineligible item → `422 { ineligibleItems: [ { productId, reason } ] }` listing **all** of them; otherwise `204`.
- [x] Catalog unavailable → `503`; never `204` when the check could not run.
- [x] Tests: service unit tests (all fine, inactive, missing, out of stock, mixed); controller slice tests (validation, `422` body, `503`); a WireMock integration test.

## Technical Notes / Constraints
- `stockStatus` has no quantities and is eventually consistent: "2 left, 5 requested" passes here and is rejected by the reservation in [task 08](08-submit-order.md). Say so in the response documentation, not in an error.
- No Inventory call here (D2).

## Implementation
Done on 2026-10-17:
- `controllers/OrderController` `POST /orders/validate` → `CheckoutCheckService.check(items)` → `204`; the Javadoc of the endpoint says that the check is soft (stock status has no quantities).
- `dto/ValidateCheckoutRequest` and `dto/OrderItemRequest` (shared with task 08): 1–100 items (`ProductCatalog.MAX_IDS`), non-null items, UUID `productId`, `quantity ≥ 1`; `validation/UniqueProductIds` (as Inventory's) rejects a product listed twice.
- `services/ProductEligibility` (shared with task 08): one Catalog answer → the ineligible items in request order, each once; missing or `NOT_ACTIVE` wins over `OUT_OF_STOCK`.
- Catalog unavailable → `DependencyUnavailableException` → `503` with `Retry-After` (task 03 handler); never `204`.
- Tests: `CheckoutCheckServiceTest` (7: all fine with one call, inactive, missing, out of stock, mixed in request order with a product both inactive and out of stock reported once, unknown stock status, Catalog down), `OrderControllerTest` (the EP-ORD-01 part: `204`, eight invalid bodies → `400` without a Catalog call, 101 items → `400`, exactly 100 accepted, `422` body, `503` with `Retry-After`), `integration/CheckoutIntegrationTest` (3 against WireMock: `204`, out of stock → `422`, Catalog `503` → `503`).

## Decisions
- **An unknown `stockStatus` (`null`) does not refuse the product.** Catalog returns it only after [Catalog task 24](../catalog-service/24-stock-status-in-batch-get.md); until then the soft check checks the status only and the reservation in task 08 catches missing stock.
