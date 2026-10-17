---
status: TODO
service: order-service
---
# EP-ORD-04 and EP-ORD-05: Order History and Order Details

## Context
A logged-in customer sees a paginated list of their own orders and the details of one order, including the shipment's TTN and status from Delivery. Only the customer's own orders are visible; another customer's order looks exactly like one that does not exist. Delivery is optional for the details page: if it is down, the order is still shown.
*References:* [Order Spec — EP-ORD-04, EP-ORD-05](../../design/services-specs/order-service-spec.md), [Delivery EP-DEL-01](../../design/services-specs/delivery-service-spec.md), [Customer Scenarios (SCN-C20, SCN-C21, SCN-C22)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [ ] `GET /orders?page=&size=` (role `CUSTOMER`): the caller's orders, newest first, default size 20, at most 100; `200 { content: [ { orderId, status, totalAmount, currency, placedAt } ], page, size, totalElements }`; invalid paging → `400`. Same paging conventions as Inventory's lists.
- [ ] `GET /orders/{orderId}` (role `CUSTOMER`, owner only, `404` otherwise): the full order with item snapshots, delivery details, totals, `placedAt`, `paidAt`, `reservationExpiresAt`, plus `ttn` and `deliveryStatus` from Delivery for orders from `PAID` on.
- [ ] Delivery unavailable, or no shipment yet → `ttn` and `deliveryStatus` are `null`; the request still succeeds.
- [ ] Tests: history paging and order; another customer's orders never appear; details for own / other / missing order; Delivery stubbed as available, without shipment, and down.

## Technical Notes / Constraints
- Ownership in the query (`id` and `customerId`), using the helper from [task 04](04-trusted-header-security.md).
- Do not call Delivery for orders still `PENDING_PAYMENT` or `EXPIRED`: there is nothing to show.
