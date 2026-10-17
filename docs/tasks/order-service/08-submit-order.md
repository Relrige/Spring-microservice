---
status: TODO
service: order-service
---
# EP-ORD-02: Submit Order and Reserve Stock

## Context
The checkout submission turns a cart into an order: prices are taken from Catalog (never from the client), stock is reserved in Inventory all-or-nothing, and the order is stored with its snapshots and `OrderPlaced` in one transaction. Two services are involved, so the order of the steps matters: a reservation must never be left without an order, because only orders expire.
*References:* [Order Spec — EP-ORD-02](../../design/services-specs/order-service-spec.md), [Inventory EP-INV-06/07](../../design/services-specs/inventory-service-spec.md), [Customer Scenarios (SCN-C14, SCN-C15)](../../design/scenarios/customer-scenarios.md), [Inventory reserve task](../inventory-service/13-reserve-stock-internal.md)

## Acceptance Criteria
- [ ] `POST /orders` with `{ items, deliveryDetails: { name, phone, address, carrier } }`; role `CUSTOMER`; `customerId` from `X-User-Id`, `customerEmail` from `X-User-Email` (missing email → `401`, logged as a gateway contract problem); IDs or emails in the body are ignored.
- [ ] Validation → `400`: *Request limits* (as task 07); every delivery field non-blank, `phone` in international format, `carrier` from the configured list (e.g. `NOVA_POSHTA`).
- [ ] Steps in this order: generate `orderId` → Catalog batch-get (missing / `NOT_ACTIVE` → `422 { ineligibleItems }`, nothing reserved) → Inventory reserve (`204` or `409` = reserved; `422` → `422 { insufficientItems }`) → one transaction: `Order` (`PENDING_PAYMENT`, snapshots with Catalog's `title` and `basePrice`, `totalAmount`, `currency = UAH`, `reservationExpiresAt = now + ORDER_PAYMENT_WINDOW`) and `OrderPlaced` in the outbox.
- [ ] `201 Created { orderId, status, totalAmount, currency, reservationExpiresAt }` with a `Location` header.
- [ ] **No orphan reservations:** a reservation call that timed out, or a failed save after a successful reservation, triggers `release(orderId)` (best-effort, logged). Then `503` or `500`.
- [ ] Catalog or Inventory unavailable → `503`; nothing created.
- [ ] Tests: service unit tests for every branch (including "reserve `409` counts as reserved" and "save fails → release called"); controller slice tests; an integration test with PostgreSQL, Kafka and WireMock for Catalog and Inventory: the order and its items are stored, prices come from Catalog even if the client sends some, `OrderPlaced` reaches the topic with key `orderId`.

## Technical Notes / Constraints
- No database transaction is open during the Catalog and Inventory calls.
- Two clicks create two orders (accepted for now, see the spec); note an `Idempotency-Key` header as a possible improvement.
- The payment window is configuration (`ORDER_PAYMENT_WINDOW`, default `15m`), so tests can use seconds.
