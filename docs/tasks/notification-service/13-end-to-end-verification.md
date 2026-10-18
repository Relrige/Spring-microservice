---
status: DONE
service: notification-service
---
# End-to-End Verification: Order Lifecycle Emails

## Context
The earlier tasks test Notification against hand-published events. This task checks the real chain through the API Gateway: Auth → Order → Inventory/Payment → Delivery → Notification → Mailpit. One customer journey should produce exactly the expected emails, at the account's address, with no duplicates. It is the acceptance check for US-CUST-12.
*References:* [US-CUST-12, US-CUST-13](../../design/user-stories/customer/customer-user-stories.md), [SCN-S09](../../design/scenarios/system-scenarios.md), [Notification Spec](../../design/services-specs/notification-service-spec.md), [Order e2e checklist](../../../e2e/manual/docker-compose-order.md)

## Acceptance Criteria
- [x] A manual checklist `e2e/manual/docker-compose-notification.md` in the style of the existing ones (stack, preparation, steps, expected result per step), run with the full Compose stack.
- [x] Journey A, *happy path*: register and log in a customer; place an order → "placed, awaiting payment" with the deadline; pay → "payment received"; the worker ships it → "shipped" with the TTN; after the mock carrier's delay → "delivered". Four emails in Mailpit, all to the account email, in this order.
- [x] Journey B, *cancel a paid order* → "cancelled" email with the conditional refund text.
- [x] Journey C, *cancel an unpaid order* → the same "cancelled" text (D3: neutral wording; no wrong "refund" promise).
- [x] Journey D, *let an order expire* (shorten `ORDER_PAYMENT_WINDOW`) → "expired" email.
- [x] Resilience: stop Notification during journey A and start it again after the delivery → all missing emails arrive, none twice, and the "delivered" email still finds its recipient (D1 lag case).
- [x] Results recorded in the task notes with dates. The spec's *Implementation Status* and the [docs README](../../README.md) (if a new e2e file is listed there) are updated.

## Technical Notes / Constraints
- Mailpit's REST API (`GET /api/v1/messages`) gives counts and recipients for the checks without reading the UI.
- Running it in Kubernetes as well is optional; note it as "not verified" if skipped, as the other services did.
- No refund flag exists in `OrderCancelled` (D3). If journey B/C feedback shows the neutral text is not good enough, record it as a follow-up for Order and Notification instead of changing the contract here.

## Implementation Notes (2026-10-18)
- **Runbook:** [`e2e/manual/docker-compose-notification.md`](../../../e2e/manual/docker-compose-notification.md), in the style of the Order runbook (Git Bash, the same setup helpers). It checks through Mailpit's REST API: `mails <orderId>` lists subjects newest first, `rcpts <orderId>` counts recipients. It is listed in `e2e/manual/README.md`. `docs/README.md` lists no e2e files, so it is unchanged.
- **Run on 2026-10-19:** Docker Compose, all nine services plus Kafka and Mailpit, through the gateway `:8080`. The runbook's code blocks were executed unchanged as a script.

| Journey | Result |
|---|---|
| A: place, pay, ship, delivered | 4 emails in order (placed → payment received → shipped → delivered), all 4 to `alice<RUN>@example.com` (the account email). The "placed" body: `999.99 UAH`, deadline `19 October 2026, 01:15 (Europe/Kyiv)` |
| B: cancel a paid order | `202`, `CANCELLED`; placed, payment received, cancelled (the conditional refund text) |
| C: cancel an unpaid order | `202`, `CANCELLED`; placed, cancelled, the **same** neutral text (D3); nothing promises a refund that did not happen |
| D: let an order expire (`ORDER_PAYMENT_WINDOW=1m`) | `EXPIRED`; placed, "expired, payment deadline missed" (items released, nothing charged) |
| Resilience: Notification stopped before the order, started after `DELIVERED` | 0 emails while down. After the start all 4 arrived to the same recipient and were still 4 after 40 s more: no duplicates. In this run the "delivered" email was sent first (the read model already had the recipient when the job ran), so `recipient_unknown` did not occur (0 log lines). The lag case itself is covered by `NotificationSenderJobIntegrationTest` |
| Personal data | `docker logs notification-service \| grep -c alice<RUN>@` → `0` |

- **How the run was set up (local files untouched):**
  - the local `.env` files were missing keys added since they were created, and `customer-service/.env` was missing, so the run used copies of every `.env.example`, plus the short Order/Delivery timings, through a temporary Compose override (`env_file: !override`);
  - the existing `spring-microservice_*` volumes of Order and Payment predate Flyway and failed `V1` (the known Order-runbook prerequisite). Instead of resetting them, the stack ran as a separate Compose project, `-p notification-e2e`, with fresh volumes. That project and its volumes were removed afterwards; the original volumes are intact.
  - The runbook's prerequisites now mention the separate-project option.
- **Kubernetes:** not verified (no cluster running), as with the other services.
- **Neutral cancellation text (D3):** journeys B and C read correctly with one text, so no follow-up for a `refunded` flag is needed now. It stays listed as *Open* in the spec.
- The spec's *Implementation Status* is updated.
