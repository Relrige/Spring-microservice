---
status: DONE
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
- [x] `EmailRenderer` (or one renderer per `NotificationType`) returns `RenderedEmail(subject, body)` for:
  - `ORDER_PLACED(orderId, totalAmount, currency, reservationExpiresAt)`
  - `PAYMENT_RECEIVED(orderId, totalAmount, currency, paidAt)`
  - `ORDER_SHIPPED(orderId, ttn)`
  - `ORDER_DELIVERED(orderId, ttn)`
  - `ORDER_CANCELLED(orderId)` and `ORDER_EXPIRED(orderId)`
- [x] Subjects exactly as in the spec, all prefixed with "VoltStore — ".
- [x] Times are formatted in `NOTIFICATION_TIME_ZONE` (default `Europe/Kyiv`, bound to `notification.time-zone`). Amounts have two decimals and the currency code (`1500.00 UAH`) and never use scientific notation.
- [x] Bodies are plain text and contain no recipient address and no other personal data.
- [x] Unit tests: one per type, checking the subject and the key facts in the body (order number, amount, deadline in the Kyiv zone across a DST change, TTN); the cancelled text mentions the conditional refund; the expired text says nothing was charged.

## Technical Notes / Constraints
- Choose between text blocks in code and template files (e.g. `src/main/resources/templates/*.txt` with simple placeholders) and record the choice in the implementation notes. A template engine (Thymeleaf, FreeMarker) is an option, but plain text with six fixed messages does not need one; discuss the trade-off before adding a dependency.
- HTML email is out of scope.

## Implementation Notes (2026-10-18)
- `email/EmailRenderer` (Spring bean, pure: no I/O) has one method per type: `orderPlaced(orderId, totalAmount, currency, reservationExpiresAt)`, `paymentReceived(…, paidAt)`, `orderShipped(orderId, ttn)`, `orderDelivered(orderId, ttn)`, `orderCancelled(orderId)`, `orderExpired(orderId)`. Each returns `RenderedEmail(subject, body)`.
- **Choice: text blocks in code**, not template files or a template engine:
  - six short plain-text messages with a few placeholders each;
  - the compiler checks each placeholder's argument;
  - a text change is reviewed next to its test.
  Template files (or Thymeleaf/FreeMarker) would pay off only with translations, HTML, or non-developers editing the texts. None of these is in scope, so no dependency was added. Long sentences use the text-block line continuation (`\`), so the plain text has no line breaks mid-sentence.
- **Formatting:**
  - subjects are exactly as in the spec, prefixed with "VoltStore — ";
  - times use `notification.time-zone` (← `NOTIFICATION_TIME_ZONE`, default `Europe/Kyiv`) as `d MMMM yyyy, HH:mm (Europe/Kyiv)`, English locale;
  - amounts use `setScale(2, HALF_UP).toPlainString()` plus the currency code;
  - every body ends with a short "automated message" signature.
- Bodies contain the order number, amounts, times and the TTN only. There is no address and no carrier: the event has none.
- **Verified (2026-10-18):** `EmailRendererTest` (9 unit tests):
  - subject and key facts per type;
  - the deadline in Kyiv time in summer, on both sides of the DST change on 25 October 2026 (00:30 and 01:30 UTC both show 03:30), and in winter;
  - `1E+3` is shown as `1000.00 UAH`;
  - the conditional refund sentence and "nothing was charged";
  - every email is plain text, has no `@`, no `<` and no unfilled `%s`.
