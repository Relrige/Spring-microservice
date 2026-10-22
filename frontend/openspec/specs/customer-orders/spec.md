# customer-orders Specification

## Purpose

The customer's view of their own orders at Order Service: the order history, the order details with delivery tracking, the status shown in the design system's words, keeping a changing order up to date, and cancellation.

## Requirements

### Requirement: Order history
`/account/orders` SHALL list the customer's orders from `GET /orders`, newest first, 20 per page with paging. Each row SHALL show the short order id, the placed date, the total and the status chip, and link to the order page. Without orders it SHALL say so with a link to the catalog.

#### Scenario: Orders listed
- **WHEN** a customer with 3 orders opens `/account/orders`
- **THEN** 3 rows are shown, newest first, each with `#` and the first 8 characters of the id, date, total and status

#### Scenario: No orders
- **WHEN** a customer without orders opens `/account/orders`
- **THEN** "You have no orders yet" and a link to the catalog are shown

### Requirement: Order status words
Order statuses SHALL be shown with the design system's words and chip colours: Awaiting payment, Paid, Ready to ship, Shipped, Delivered, Cancelling, Cancelled, Expired. Delivery statuses SHALL be shown as TTN pending, TTN ready, TTN failed, In transit, Delivered and Cancelled.

#### Scenario: Status chip
- **WHEN** an order has status `PENDING_SHIPPING`
- **THEN** its chip reads "Ready to ship"

### Requirement: Order details
`/account/orders/:orderId` SHALL show the order from `GET /orders/{orderId}`: short id, status, placed and paid dates, the items with their order-time prices and line totals, the total, and the delivery details. When the answer has a TTN or a delivery status, they SHALL be shown; when they are missing, the delivery part SHALL say that tracking appears after the parcel is prepared.

#### Scenario: Paid order with a TTN
- **WHEN** a customer opens a `PENDING_SHIPPING` order with TTN `20450000000001`
- **THEN** the page shows the items, the total, "Ready to ship" and the TTN

#### Scenario: Order of another customer or unknown
- **WHEN** the order is answered `404`
- **THEN** "Order not found" is shown with a link to the order history

#### Scenario: Delivery data missing
- **WHEN** a paid order's details have no TTN and no delivery status
- **THEN** the delivery part says that tracking appears once the parcel is prepared, and no error is shown

### Requirement: Paying from the order page
An order awaiting payment SHALL show its pay-by time and a "Pay now" link to its payment step.

#### Scenario: Unpaid order
- **WHEN** a customer opens an order with status `PENDING_PAYMENT`
- **THEN** the pay-by time and "Pay now" linking to `/checkout/{orderId}` are shown

### Requirement: Order kept up to date
While an order page or the order history shows an order whose status can still change on its own (`PENDING_PAYMENT`, `PAID`, `PENDING_SHIPPING`, `SHIPPED`, `CANCELLATION_REQUESTED`) and the browser tab is visible, the frontend SHALL read it again every 5 seconds (BL-13). It SHALL stop for `DELIVERED`, `CANCELLED` and `EXPIRED`.

#### Scenario: TTN arrives
- **WHEN** a `PAID` order page is open and the order becomes `PENDING_SHIPPING` with a TTN
- **THEN** within about 5 seconds the page shows "Ready to ship" and the TTN without a reload

#### Scenario: Final status
- **WHEN** the shown order is `DELIVERED`
- **THEN** no further order requests are sent while the page stays open

### Requirement: Cancel an order
An order in `PENDING_PAYMENT`, `PAID` or `PENDING_SHIPPING` SHALL offer "Cancel order" after a confirmation that says whether a refund follows (paid orders). Cancelling SHALL send `DELETE /orders/{orderId}` and show the status from the `202` answer: Cancelled, or Cancelling with the note that the refund and release finish shortly. Other statuses SHALL show no cancel control.

#### Scenario: Cancel an unpaid order
- **WHEN** the customer cancels an order awaiting payment and confirms
- **THEN** `DELETE /orders/{orderId}` is sent and the order shows "Cancelled"

#### Scenario: Cancellation still running
- **WHEN** the cancellation is answered `202` with status `CANCELLATION_REQUESTED`
- **THEN** the order shows "Cancelling" with the note, and is read again until it is cancelled

#### Scenario: Cancel not possible any more
- **WHEN** the cancellation is answered `409` with `orderStatus` `SHIPPED`
- **THEN** the message says the order can no longer be cancelled and the page shows the order as shipped

#### Scenario: Shipped order
- **WHEN** a customer opens a `SHIPPED` order
- **THEN** no "Cancel order" control is shown
