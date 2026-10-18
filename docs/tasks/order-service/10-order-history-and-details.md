---
status: DONE
service: order-service
---
# EP-ORD-04 and EP-ORD-05: Order History and Order Details

## Context
A logged-in customer sees a paginated list of their own orders and the details of one order, including the shipment's TTN and status from Delivery. Only the customer's own orders are visible; another customer's order looks exactly like one that does not exist. Delivery is optional for the details page: if it is down, the order is still shown.
*References:* [Order Spec — EP-ORD-04, EP-ORD-05](../../design/services-specs/order-service-spec.md), [Delivery EP-DEL-01](../../design/services-specs/delivery-service-spec.md), [Customer Scenarios (SCN-C20, SCN-C21, SCN-C22)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [x] `GET /orders?page=&size=` (role `CUSTOMER`): the caller's orders, newest first, default size 20, at most 100; `200 { content: [ { orderId, status, totalAmount, currency, placedAt } ], page, size, totalElements }`; invalid paging → `400`. Same paging conventions as Inventory's lists.
- [x] `GET /orders/{orderId}` (role `CUSTOMER`, owner only, `404` otherwise): the full order with item snapshots, delivery details, totals, `placedAt`, `paidAt`, `reservationExpiresAt`, plus `ttn` and `deliveryStatus` from Delivery for orders from `PAID` on.
- [x] Delivery unavailable, or no shipment yet → `ttn` and `deliveryStatus` are `null`; the request still succeeds.
- [x] Tests: history paging and order; another customer's orders never appear; details for own / other / missing order; Delivery stubbed as available, without shipment, and down.

## Technical Notes / Constraints
- Ownership in the query (`id` and `customerId`), using the helper from [task 04](04-trusted-header-security.md).
- Do not call Delivery for orders still `PENDING_PAYMENT` or `EXPIRED`: there is nothing to show.

## Implementation
Done on 2026-10-17:
- `controllers/OrderController` `GET /orders?page=&size=` and `GET /orders/{orderId}` → `services/OrderQueryService`.
- **History:** `paging/Paging.of(page, size, sort)` with Inventory's limits (zero-based page, size 1–100, default 20; out of range → `InvalidQueryParameterException` → `400` with `errors: { page | size: [...] }`; a non-number → `400`), sorted `placedAt DESC, id DESC` (the ID keeps page boundaries stable), answered in `paging/PageResponse` (copied from Inventory) with `dto/OrderSummaryResponse`.
- **Details:** `OrderAccess.loadOwned` (`404` for missing or foreign), `dto/OrderDetailsResponse` with items, delivery details, totals and timestamps. Delivery is asked only when `paidAt` is set, that is for orders paid at some point (including ones cancelled after payment); `PENDING_PAYMENT`, `EXPIRED` and orders cancelled before payment never call it. No shipment yet, an unknown status, or Delivery unavailable → `ttn` and `deliveryStatus` are `null` (the last one logged as a warning).
- `OrderRoutesProbeController` now stands in only for cancel, the fulfillment queue and ship; `OrderAccessIntegrationTest` reaches the real details endpoint.
- Tests: `OrderQueryServiceTest` (7: history paging, sort and envelope; invalid page and size; unpaid → no Delivery call; paid with TTN; no shipment yet; Delivery down; cancelled after payment), `OrderControllerTest` (default page, invalid paging, details body, `404`), `integration/PaymentAndQueriesIntegrationTest` (5: history only the caller's orders, newest first, over two pages, `size=101` → `400`; details with Delivery's TTN; Delivery down → order still shown; unpaid → Delivery not called; another customer's order → `404`).

## Decisions
- **"Paid at some point" is `paidAt != null`**, not a list of statuses: it also covers an order cancelled after payment, whose shipment Delivery cancelled and still reports.
