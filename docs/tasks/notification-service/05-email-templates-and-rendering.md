---
status: TODO
service: notification-service
---
# Email Templates and Rendering

## Context
A listener stores each notification with its subject and body **already rendered** (spec *Notification log*), so the sender job stays generic and the row shows exactly what was sent. This task builds the rendering as a pure component: input is typed data taken from the events, output is a subject and a plain-text body. Unit tests cover it without Spring or Kafka.

The texts are given per event in EP-NOTIF-EVT-01…05. Points to watch:
- the `OrderPlaced` email says "awaiting payment" and gives the deadline from `reservationExpiresAt`;
- the `OrderPaid` email is a receipt (amount and `paidAt`), not itemised;
- the `OrderShipped` email has the TTN but no carrier;
- the `CUSTOMER_CANCELLED` text is neutral: "if the order was already paid, the refund has been initiated" (decision D3).

*References:* [Notification Spec — EP-NOTIF-EVT-01…05, Sending email](../../design/services-specs/notification-service-spec.md), [SCN-S09](../../design/scenarios/system-scenarios.md)

## Acceptance Criteria
- [ ] `EmailRenderer` (or one renderer per `NotificationType`) returns `RenderedEmail(subject, body)` for:
  - `ORDER_PLACED(orderId, totalAmount, currency, reservationExpiresAt)`
  - `PAYMENT_RECEIVED(orderId, totalAmount, currency, paidAt)`
  - `ORDER_SHIPPED(orderId, ttn)`
  - `ORDER_DELIVERED(orderId, ttn)`
  - `ORDER_CANCELLED(orderId)` and `ORDER_EXPIRED(orderId)`
- [ ] Subjects exactly as in the spec, all prefixed with "VoltStore — ".
- [ ] Times are formatted in `NOTIFICATION_TIME_ZONE` (default `Europe/Kyiv`, bound to `notification.time-zone`). Amounts have two decimals and the currency code (`1500.00 UAH`) and never use scientific notation.
- [ ] Bodies are plain text and contain no recipient address and no other personal data.
- [ ] Unit tests: one per type, checking the subject and the key facts in the body (order number, amount, deadline in the Kyiv zone across a DST change, TTN); the cancelled text mentions the conditional refund; the expired text says nothing was charged.

## Technical Notes / Constraints
- Choose between text blocks in code and template files (e.g. `src/main/resources/templates/*.txt` with simple placeholders) and record the choice in the implementation notes. A template engine (Thymeleaf, FreeMarker) is an option, but plain text with six fixed messages does not need one; discuss the trade-off before adding a dependency.
- HTML email is out of scope.
