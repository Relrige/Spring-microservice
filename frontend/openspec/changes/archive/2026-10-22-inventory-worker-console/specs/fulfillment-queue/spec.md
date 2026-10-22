# Spec Delta

## Purpose

The inventory worker's dispatch work across Order and Delivery Service: the queue of paid orders with their delivery state, marking an order as shipped once its TTN is ready, and retrying a TTN generation that failed.

## ADDED Requirements

### Requirement: Fulfillment queue
`/warehouse/queue` SHALL list the orders from `GET /orders/fulfillment-queue`, oldest payment first. Each order SHALL show its short id, the payment time, the items (title × quantity), the recipient, phone, carrier and address, the delivery status chip, the TTN when there is one, and the failure reason when the TTN failed. An empty queue SHALL say that no orders are waiting.

#### Scenario: Orders waiting
- **WHEN** two paid orders wait for dispatch
- **THEN** both are listed, the one paid first at the top, each with its items and delivery details

#### Scenario: Empty queue
- **WHEN** the queue answer has no orders
- **THEN** "No orders are waiting for dispatch" is shown

### Requirement: Delivery data unavailable
When the queue answer has `deliveryDataAvailable: false`, the page SHALL say that delivery statuses cannot be loaded right now, show the orders without delivery status, and offer no "Mark as shipped" and no "Retry TTN".

#### Scenario: Delivery Service down
- **WHEN** the queue is answered with `deliveryDataAvailable: false`
- **THEN** the notice is shown and no order offers "Mark as shipped"

### Requirement: Queue kept up to date
While the queue page is open and the tab is visible, the queue SHALL be read again every 5 seconds (BL-13), so TTNs that arrive and orders shipped elsewhere show without a reload.

#### Scenario: TTN arrives
- **WHEN** an order in the open queue gets its TTN
- **THEN** within about 5 seconds the order shows "TTN ready", the TTN and "Mark as shipped"

### Requirement: Mark as shipped
An order whose delivery status is `TTN_GENERATED` SHALL offer "Mark as shipped" after a confirmation naming the TTN. It SHALL send `POST /orders/{orderId}/ship`; on `200` the order SHALL leave the queue with a confirmation "Order shipped". A `409` SHALL say the order can no longer be shipped as shown and read the queue again; a `503` SHALL show the unavailable-service message. Orders without a ready TTN SHALL offer no "Mark as shipped".

#### Scenario: Order shipped
- **WHEN** the worker marks an order with a ready TTN as shipped and confirms
- **THEN** `POST /orders/{orderId}/ship` is sent, "Order shipped" is shown and the order leaves the queue

#### Scenario: TTN not ready
- **WHEN** an order's delivery status is `TTN_PENDING`
- **THEN** it shows "TTN pending" and no "Mark as shipped"

#### Scenario: Shipped elsewhere
- **WHEN** the ship request is answered `409`
- **THEN** the message says the order changed and the queue is read again

### Requirement: Retry a failed TTN
An order whose delivery status is `TTN_FAILED` SHALL show the failure reason and offer "Retry TTN", which sends `POST /delivery/orders/{orderId}/ttn/retry` once. On `200` the order SHALL show the new TTN. On `424` the reason SHALL be shown; a reason `packaging_data_missing:{productId}` SHALL name the product and link to its row on the stock page. A `409` SHALL read the queue again.

#### Scenario: Retry succeeds
- **WHEN** the worker retries a failed TTN and the answer is `200` with a TTN
- **THEN** the order shows "TTN ready", the TTN and "Mark as shipped"

#### Scenario: Packaging still missing
- **WHEN** the retry is answered `424` with reason `packaging_data_missing` for a product of the order
- **THEN** the message names that product as having no packaging and links to the stock page searched for it
