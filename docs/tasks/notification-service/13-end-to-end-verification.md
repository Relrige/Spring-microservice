---
status: TODO
service: notification-service
---
# End-to-End Verification: Order Lifecycle Emails

## Context
The earlier tasks test Notification against hand-published events. This task checks the real chain through the API Gateway: Auth → Order → Inventory/Payment → Delivery → Notification → Mailpit. One customer journey should produce exactly the expected emails, at the account's address, with no duplicates. It is the acceptance check for US-CUST-12.
*References:* [US-CUST-12, US-CUST-13](../../design/user-stories/customer/customer-user-stories.md), [SCN-S09](../../design/scenarios/system-scenarios.md), [Notification Spec](../../design/services-specs/notification-service-spec.md), [Order e2e checklist](../../../e2e/manual/docker-compose-order.md)

## Acceptance Criteria
- [ ] A manual checklist `e2e/manual/docker-compose-notification.md` in the style of the existing ones (stack, preparation, steps, expected result per step), run with the full Compose stack.
- [ ] Journey A, *happy path*: register and log in a customer; place an order → "placed, awaiting payment" with the deadline; pay → "payment received"; the worker ships it → "shipped" with the TTN; after the mock carrier's delay → "delivered". Four emails in Mailpit, all to the account email, in this order.
- [ ] Journey B, *cancel a paid order* → "cancelled" email with the conditional refund text.
- [ ] Journey C, *cancel an unpaid order* → the same "cancelled" text (D3: neutral wording; no wrong "refund" promise).
- [ ] Journey D, *let an order expire* (shorten `ORDER_PAYMENT_WINDOW`) → "expired" email.
- [ ] Resilience: stop Notification during journey A and start it again after the delivery → all missing emails arrive, none twice, and the "delivered" email still finds its recipient (D1 lag case).
- [ ] Results recorded in the task notes with dates. The spec's *Implementation Status* and the [docs README](../../README.md) (if a new e2e file is listed there) are updated.

## Technical Notes / Constraints
- Mailpit's REST API (`GET /api/v1/messages`) gives counts and recipients for the checks without reading the UI.
- Running it in Kubernetes as well is optional; note it as "not verified" if skipped, as the other services did.
- No refund flag exists in `OrderCancelled` (D3). If journey B/C feedback shows the neutral text is not good enough, record it as a follow-up for Order and Notification instead of changing the contract here.
