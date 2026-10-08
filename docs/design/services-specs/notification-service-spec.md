# Notification Service — Implementation Spec

A pure event consumer responsible for dispatching transactional emails to customers. It has no REST API and owns no persistent business entities. Its sole responsibility is to react to domain events emitted by other services and send the appropriate email. Keeping email dispatch decoupled from transactional flows ensures that a slow or failed email provider never blocks order processing.

Error handling strategy for failed email delivery (retry policy, dead-letter queues) is deferred.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | none (may maintain an internal retry/outbox log, but no business entities) |
| **Events emitted** | none |
| **Events consumed** | `OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderDelivered`, `OrderCancelled` |
| **REST API** | none |

---

## EP-NOTIF-EVT-01: Consume `OrderPlaced`

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** A new order is submitted and stock is reserved; order status transitions to `PENDING_PAYMENT`.

**Payload received:** `{ orderId, customerEmail, totalAmount, currency }`

**Steps:**
1. Receive and deserialise the `OrderPlaced` event.
2. Extract `customerEmail` and order details from the payload.
3. Compose "Your order has been placed" email:
   - Subject: "VoltStore — Order #`{orderId}` confirmed"
   - Body: order summary (`orderId`, `totalAmount`, `currency`), payment instructions / link, and a note that the reservation expires in 15 minutes if unpaid.
4. Dispatch the email to `customerEmail` via the email provider.
5. Acknowledge the message.

**Edge cases:**
- `customerEmail` is null or malformed → log an error and acknowledge (do not block the queue for a bad address)
- Email provider unavailable → retry according to the deferred retry policy; if retries exhausted, move to dead-letter queue

**External calls:**
- Email provider (SMTP / third-party API) — async fire-and-forget

**Refs:** US-CUST-12, SCN-C14 (step 6), SCN-S09, N-10

---

## EP-NOTIF-EVT-02: Consume `OrderPaid`

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** Successful payment confirmed; order status transitions to `PAID`.

**Payload received:** `{ orderId, customerId (nullable), customerEmail }`

**Steps:**
1. Receive and deserialise the `OrderPaid` event.
2. Compose "Payment received" email:
   - Subject: "VoltStore — Payment received for Order #`{orderId}`"
   - Body: confirmation that payment was successful, note that the parcel is being prepared and a tracking number will follow.
3. Dispatch email to `customerEmail`.
4. Acknowledge the message.

**Edge cases:**
- `customerEmail` missing → log error and acknowledge
- Email provider failure → retry / dead-letter (deferred policy)

**External calls:**
- Email provider — async fire-and-forget

**Refs:** US-CUST-12, SCN-C16 (step 11), SCN-S09, N-10

---

## EP-NOTIF-EVT-03: Consume `OrderShipped`

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** Inventory worker confirms parcel handover to carrier; order status transitions to `SHIPPED`.

**Payload received:** `{ orderId, ttn, customerEmail }`

**Steps:**
1. Receive and deserialise the `OrderShipped` event.
2. Compose "Your parcel is on its way" email:
   - Subject: "VoltStore — Order #`{orderId}` has been shipped"
   - Body: TTN (tracking number), carrier name, and a link / instructions for parcel tracking.
3. Dispatch email to `customerEmail`.
4. Acknowledge the message.

**Edge cases:**
- `ttn` is null (should not happen if workflow is correct, but defensive) → send email without tracking number; include a note that tracking info will follow
- Email provider failure → retry / dead-letter (deferred policy)

**External calls:**
- Email provider — async fire-and-forget

**Refs:** US-CUST-12, US-CUST-16, SCN-INV06 (step 7), SCN-S09, N-10

---

## EP-NOTIF-EVT-04: Consume `OrderDelivered`

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** Carrier confirms delivery; order status transitions to `DELIVERED`.

**Payload received:** `{ orderId, ttn, customerEmail }`

**Steps:**
1. Receive and deserialise the `OrderDelivered` event.
2. Compose "Your parcel has been delivered" email:
   - Subject: "VoltStore — Order #`{orderId}` delivered"
   - Body: delivery confirmation, `ttn`, and a thank-you message.
3. Dispatch email to `customerEmail`.
4. Acknowledge the message.

**Edge cases:**
- Email provider failure → retry / dead-letter (deferred policy)

**External calls:**
- Email provider — async fire-and-forget

**Refs:** US-CUST-12, US-CUST-16, SCN-S08 (step 6), SCN-S09, N-10

---

## EP-NOTIF-EVT-05: Consume `OrderCancelled`

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** Either the cancellation saga completes successfully, or a reservation TTL expires (both produce `OrderCancelled`).

**Payload received:** `{ orderId, customerEmail, reason }` — `reason` distinguishes `"CUSTOMER_CANCELLED"` vs. `"EXPIRED"` so the email copy can differ.

**Steps:**
1. Receive and deserialise the `OrderCancelled` event.
2. Branch on `reason`:
   - `CUSTOMER_CANCELLED` (paid order cancelled by customer):
     - Subject: "VoltStore — Order #`{orderId}` cancelled — refund initiated"
     - Body: cancellation confirmed, refund initiated (processing time note).
   - `EXPIRED` (payment TTL elapsed, no charge made):
     - Subject: "VoltStore — Order #`{orderId}` expired — payment deadline missed"
     - Body: reservation released, no charge made, invite to re-order.
3. Dispatch email to `customerEmail`.
4. Acknowledge the message.

**Edge cases:**
- `reason` not recognised → send a generic cancellation email and log a warning
- Email provider failure → retry / dead-letter (deferred policy)

**External calls:**
- Email provider — async fire-and-forget

**Refs:** US-CUST-12, US-CUST-13, SCN-S06 (step 6), SCN-S07 (step 6), SCN-S09, N-10
