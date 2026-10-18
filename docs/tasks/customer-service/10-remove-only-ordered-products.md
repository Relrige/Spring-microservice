---
status: DONE
service: customer-service
---
# OrderPaid Removes Only the Ordered Products

## Context
EP-CUST-EVT-01 cleared the whole cart when an order was paid. A customer may pay up to the payment window (15 minutes) after submitting and keep shopping meanwhile; products added in that time were deleted as well. A redelivered or late `OrderPaid` could also empty a cart filled again afterwards. Found in the business-logic review of 2026-10-18 ([Order task 17](../order-service/17-review-fixes.md)).
*References:* [Customer Spec — EP-CUST-EVT-01](../../design/services-specs/customer-service-spec.md), [task 08](08-consume-order-paid.md)

## Acceptance Criteria
- [x] `OrderPaid` removes the lines of the products in its `items`; every other line stays.
- [x] Lines are removed, not reduced by the ordered quantity (a reduction applied twice by a redelivered event would take away items added later).
- [x] A payload without `items` is a poison message (straight to the DLT).

## Implementation
Done on 2026-10-18:
- `events/OrderPaidEvent` reads `items: [ { productId } ]` (required, validated); `Cart.removeAll(productIds)` removes those lines and skips missing ones; `CartService.removeOrderedProducts` locks the cart as before (`changeIfExists`, no cart → nothing created); `OrderEventsListener` calls it. `clearCart` stays for EP-CUST-09.
- Tests: `CartTest` (removes only the given products, skips missing ones, a repeat changes nothing), `OrderPaidConsumerIntegrationTest` (ordered products removed and the cart row kept; **a product added after submitting stays**; the same event twice removes only the ordered product; a payload without `items` goes to the DLT; the guest, missing-cart, skipped-type and retry tests now send the cart's real product IDs).
- All Customer tests pass except `CartRepositoryTest.changes_moveUpdatedAtForward`, which compares a database timestamp with the JVM clock and fails only while the local Docker VM's clock is three hours ahead (unrelated to this change).
