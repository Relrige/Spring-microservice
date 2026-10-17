---
status: DONE
service: order-service
---
# EP-ORD-02: Submit Order and Reserve Stock

## Context
The checkout submission turns a cart into an order: prices are taken from Catalog (never from the client), stock is reserved in Inventory all-or-nothing, and the order is stored with its snapshots and `OrderPlaced` in one transaction. Two services are involved, so the order of the steps matters: a reservation must never be left without an order, because only orders expire.
*References:* [Order Spec — EP-ORD-02](../../design/services-specs/order-service-spec.md), [Inventory EP-INV-06/07](../../design/services-specs/inventory-service-spec.md), [Customer Scenarios (SCN-C14, SCN-C15)](../../design/scenarios/customer-scenarios.md), [Inventory reserve task](../inventory-service/13-reserve-stock-internal.md)

## Acceptance Criteria
- [x] `POST /orders` with `{ items, deliveryDetails: { name, phone, address, carrier } }`; role `CUSTOMER`; `customerId` from `X-User-Id`, `customerEmail` from `X-User-Email` (missing email → `401`, logged as a gateway contract problem); IDs or emails in the body are ignored.
- [x] Validation → `400`: *Request limits* (as task 07); every delivery field non-blank, `phone` in international format, `carrier` from the configured list (e.g. `NOVA_POSHTA`).
- [x] Steps in this order: generate `orderId` → Catalog batch-get (missing / `NOT_ACTIVE` → `422 { ineligibleItems }`, nothing reserved) → Inventory reserve (`204` or `409` = reserved; `422` → `422 { insufficientItems }`) → one transaction: `Order` (`PENDING_PAYMENT`, snapshots with Catalog's `title` and `basePrice`, `totalAmount`, `currency = UAH`, `reservationExpiresAt = now + ORDER_PAYMENT_WINDOW`) and `OrderPlaced` in the outbox.
- [x] `201 Created { orderId, status, totalAmount, currency, reservationExpiresAt }` with a `Location` header.
- [x] **No orphan reservations:** a reservation call that timed out, or a failed save after a successful reservation, triggers `release(orderId)` (best-effort, logged). Then `503` or `500`.
- [x] Catalog or Inventory unavailable → `503`; nothing created.
- [x] Tests: service unit tests for every branch (including "reserve `409` counts as reserved" and "save fails → release called"); controller slice tests; an integration test with PostgreSQL, Kafka and WireMock for Catalog and Inventory: the order and its items are stored, prices come from Catalog even if the client sends some, `OrderPlaced` reaches the topic with key `orderId`.

## Technical Notes / Constraints
- No database transaction is open during the Catalog and Inventory calls.
- Two clicks create two orders (accepted for now, see the spec); note an `Idempotency-Key` header as a possible improvement.
- The payment window is configuration (`ORDER_PAYMENT_WINDOW`, default `15m`), so tests can use seconds.

## Implementation
Done on 2026-10-17:
- `controllers/OrderController` `POST /orders` → `OrderPlacementService.place(customer, request)` → `201` with `Location: /orders/{orderId}` and `dto/PlacedOrderResponse`.
- `dto/PlaceOrderRequest` (items as task 07, `@Valid` `deliveryDetails`) and `dto/DeliveryDetailsRequest`: every field non-blank, `name` ≤ 200 and `address` ≤ 500 (the column sizes), `phone` in E.164 (`+` and 8–15 digits), `carrier` checked by `validation/OfferedCarrier` against `order.checkout.carriers`. Its `toString()` prints the carrier only (personal data). The body has no customer, email or price fields, so such fields are ignored.
- `services/OrderPlacementService`, steps in the spec's order: missing email → `MissingCustomerEmailException` (`401`, logged as an Auth/gateway contract problem, before any call) → new `orderId` → Catalog (missing / `NOT_ACTIVE` → `422 { ineligibleItems }`, nothing reserved) → Inventory reserve (`Reserved`, which the adapter also returns for `409`; `Insufficient` → `422 { insufficientItems }`) → `TransactionOperations.execute`: `OrderEntity.place` with Catalog's titles and prices, `currency = UAH`, the payment window from configuration, `orders.save`, `outbox.append(OrderPlacedEvent)`. No transaction is open during the remote calls (the service is not `@Transactional`; Boot's `TransactionTemplate` wraps only the save).
- **No orphan reservations:** Inventory unavailable (timeout, `5xx`, open circuit) or a failed save → `release(orderId)`, best effort; a failing release is logged as needing an operator and never hides the original error (`503` or `500`).
- `config/CheckoutProperties` (`order.checkout.payment-window` from `ORDER_PAYMENT_WINDOW`, default `15m`; `order.checkout.carriers` from `ORDER_CARRIERS`, default `NOVA_POSHTA`) and `config/CheckoutConfig` (a `Clock` bean). Both variables are in `.env.example` and `k8s/order/app-config.yaml`.
- `OrderRoutesProbeController` no longer stands in for `POST /orders/validate` and `POST /orders`; `SecurityConfigTest` and `RouteSecurityRulesTest` load the real `OrderController` with the services mocked.
- Tests: `OrderPlacementServiceTest` (11: stored order with Catalog's titles and prices, total, window and `OrderPlaced`; step order and the same `orderId` for the reservation; `Reserved` (Inventory `409`) is not released; inactive or unknown → nothing reserved; Catalog's stock status not checked here; insufficient → no order and no release; Catalog down; Inventory down → release; save fails → release; failing release does not hide the error; no email → no call at all), `OrderControllerTest` (the EP-ORD-02 part: `201` with `Location` and body, six invalid delivery details, no delivery details, duplicate product, the rejected phone not echoed, `422` × 2, `503`, `401`), `integration/CheckoutIntegrationTest` (4 on PostgreSQL 18, Kafka 4.1 and WireMock: the order and items stored with Catalog's prices although the body sends prices, a total, a customer and an email; Inventory asked under the same `orderId`; `OrderPlaced` on the topic with key `orderId`; Inventory `422` → `422` and nothing stored; inactive → no reservation call; Inventory down → `503`, release called, nothing stored).

## Decisions
Taken on 2026-10-17 (proposed during implementation):
- **Carriers are configuration, default `NOVA_POSHTA` only**: it is the only carrier Delivery and the documents use. Adding one means checking that Delivery can register it, then extending `ORDER_CARRIERS`.
- **Release after any Inventory failure, not only a timeout.** Inside Order a timeout, a `5xx` and an open circuit look the same (`DependencyUnavailableException`), and a release is idempotent and harmless when nothing is reserved.
- **Catalog's `stockStatus` is not checked at submission**: the reservation knows the quantities, and a stale "out of stock" would refuse an order Inventory could serve.

## Not done here
- **All orders get `401` until [Auth task 09](../auth-service/09-email-claim-in-jwt.md) and [Gateway task 11](../api-getaway/11-forward-user-email-header.md) are done**: today nothing sends `X-User-Email`.
- **A late reservation can still be orphaned:** if Inventory reserves *after* Order's timeout and after the release has arrived, the stock stays reserved, because Inventory reservations do not expire on their own. Rare (it needs a reservation slower than the read timeout plus retries); an expiry of reservations in Inventory, or a periodic check for reservations without an order, would close it.
- Two clicks still create two orders (accepted in the spec); an `Idempotency-Key` header would prevent it.
