---
status: DONE
service: order-service
---
# Fixes from the Business-Logic Review

## Context
A review of the flows across the services (2026-10-18) found four logic problems where a customer could lose items, money or stock, or where Order's API was looser than the shop's rules. Two of them were already recorded as known gaps in tasks 08 and 09.
*References:* [Order Spec — EP-ORD-02, EP-ORD-03, EP-ORD-JOB-03/04](../../design/services-specs/order-service-spec.md), [Customer Spec — EP-CUST-EVT-01](../../design/services-specs/customer-service-spec.md), [Customer task 10](../customer-service/10-remove-only-ordered-products.md)

## Acceptance Criteria
- [x] **1. Paying no longer empties the whole cart:** Customer removes only the ordered products ([Customer task 10](../customer-service/10-remove-only-ordered-products.md)).
- [x] **2. Order enforces the cart's quantity limit and a payable total:** `quantity` 1–10 (`400` otherwise); a total above `9,999,999,999.99` is `422 order-total-too-large` before anything is reserved, instead of a `500` after the reservation.
- [x] **3. No reservation is ever left without an order:** a reservation attempt is stored before the Inventory call and deleted with the order; EP-ORD-JOB-03 releases the attempts that outlive the settle time.
- [x] **4. A refund owed after a lost payment race is never forgotten:** stored before the first attempt, deleted when Payment confirms, repeated by EP-ORD-JOB-04 with back-off, flagged after 5 failures.

## Implementation
Done on 2026-10-18:
- `V3__create_recovery_tables.sql`: `reservation_attempts (order_id, created_at, release_attempts, last_error)` and `pending_refunds (order_id, amount, currency, created_at, attempts, last_attempt_at, last_error, needs_manual_attention)`; entities `ReservationAttempt`, `PendingRefund` and their repositories.
- **Fix 2:** `OrderItemRequest` `@Max(10)` (`MAX_QUANTITY`, the cart's INV-CART-02). `OrderPlacementService` sums Catalog's prices × quantities and throws `OrderTotalTooLargeException` (`422`, `maxTotal`) above `MAX_TOTAL`, the 10 integer digits of Payment's `amount` and of `orders.total_amount`.
- **Fix 3:** `OrderPlacementService` saves the attempt before `reserve`, deletes it when Inventory reports insufficient stock (nothing reserved) and in the transaction that stores the order; on an Inventory failure or a failed save it releases at once and keeps the attempt. `jobs/OrphanReservationJob` (every `order.recovery.interval`, `ORDER_RECOVERY_INTERVAL`, default `1m`) handles attempts older than `order.recovery.reservation-settle` (`2m`): an attempt whose order exists is deleted; otherwise release, then delete; a failed release is counted, with an `ERROR` at the fifth.
- **Fix 4:** `services/OwedRefunds.owe(order)` stores a `PendingRefund` (once), then `attempt(orderId)`: `Refunded` / `AlreadyRefunded` → deleted; `RefundFailed` → counted, warning, one `ERROR` when flagged after `order.cancellation.manual-attention-after` (5). `OrderPaymentService` calls `owe` instead of refunding directly. `jobs/PendingRefundJob` repeats due refunds with the cancellation's back-off.
- Configuration: `order.recovery.*` in `application.yml`, `ORDER_RECOVERY_INTERVAL` in `.env.example`, `k8s/order/app-config.yaml` and the spec; the test properties set the interval to 1 h, so the jobs never run in the background of tests.
- Tests (331 in Order, all passing): `OrderPlacementServiceTest` (attempt saved before the reservation and deleted with the order, deleted on insufficient stock, kept when Inventory is down or the save fails, a total one cent above the limit refused before any call, the exact limit accepted), `OrderPaymentServiceTest` (a lost race records the refund as owed), `OwedRefundsTest` (5: stored before the attempt and deleted on confirmation, already refunded, a failure stays owed and is counted, the fifth failure flags and alerts once, owed twice is stored once), `RecoveryJobsTest` (7: settle time, release then delete, existing order only deleted, failed release counted, refund back-off including flagged refunds, one failure does not stop either run), `OrderControllerTest` (quantity 11 → `400`, `422 order-total-too-large`), `CheckoutIntegrationTest` (no attempt left after a placed order; an attempt kept after an Inventory outage; **an orphan reservation whose release failed is released by the job once it has settled, and not before**), `PaymentAndQueriesIntegrationTest` (**a refund rejected after the lost race stays owed with its error, and the job pays it back and deletes it**).

## Decisions
Taken on 2026-10-18:
- **Write down what is owed, before acting**, rather than an expiry of reservations in Inventory. Inventory cannot know how long a reservation is needed (a paid order keeps it until dispatch, which can take days), but Order knows exactly which reservations belong to no order.
- **The settle time (2 minutes) is far longer than a reservation call** (Inventory read timeout 3 s × 3 attempts plus back-off), so the job never releases a reservation whose call is still running and never races a late reservation.
- **No claims for the two jobs:** release is idempotent in Inventory and Payment refunds an order at most once, so two replicas doing the same work are harmless.
- **Quantity limit 10** is the cart's own rule, reused rather than invented; the total check is separate, because 10 × the largest price Catalog accepts would still overflow.

## Not done here
- Notification Service does not exist, so customers are not told about expired orders or TTNs by email (found in the same review).
