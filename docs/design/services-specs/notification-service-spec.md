# Notification Service — Implementation Spec

A pure event consumer that sends transactional emails to customers. It has no business REST API and owns no business entities. It reacts to the order lifecycle events of Order Service and the delivery report of Delivery Service, and emails the customer. Email is decoupled from the transactional flows, so a slow or failed email provider never blocks ordering, payment or shipping.

Sending is **at-least-once and asynchronous to the listener**: a listener only records *what* must be sent, in its own database, and a scheduled sender job does the SMTP work with retries (the same split as Delivery's `OrderPaid` consumer and TTN generation job).

> **Revision note (2026-10-18, before implementation).** Rewritten after the Order (2026-10-16) and Delivery (2026-10-15) revisions, so that every consumed event matches what those services publish. The first version predated them: it expected `OrderDelivered` from Order with a `customerEmail`, and left retries and duplicates open. Differences are listed under *Changes from the First Spec Version*; the design questions are settled under *Decisions*.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | none (no domain model) |
| **Technical tables** | `notifications` (one row per email to send: dedup key, rendered message, sending state); `order_recipients` (read model `orderId → customerEmail`, built from order events) |
| **Events emitted** | none |
| **Events consumed** | `OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderCancelled` (Order Service, topic `order.order-events`); `OrderDelivered` (Delivery Service, topic `delivery.shipment-events`) |
| **Synchronous dependencies** | SMTP server, through the `EmailSender` port (Mailpit in Compose and Kubernetes) |
| **Callers** | none; no gateway route, no internal route |
| **Database** | own PostgreSQL database (database per service), schema managed by Flyway |

---

## Cross-cutting Conventions

### Security and routing

Notification has no business endpoints. Following the other services, every path has an explicit rule and anything else is denied (`denyAll`): only `/actuator/health/**` (probes) and `/actuator/prometheus` (scraping, see the [monitoring report](../../reports/monitoring-alerting.md)) are open. The gateway never routes to Notification. No outgoing call carries user headers or `X-Internal-Token`, since the only outgoing connection is SMTP.

### Consuming events

| | Order events | Delivery events |
|---|---|---|
| **Topic** | `order.order-events` (owned by Order; name from `ORDER_EVENTS_TOPIC`) | `delivery.shipment-events` (owned by Delivery; name from `DELIVERY_EVENTS_TOPIC`) |
| **Handled `eventType`s** | `OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderCancelled` | `OrderDelivered` (`TTNGenerated` is skipped) |
| **Dead-letter topic** (owned and declared by Notification, 1 partition) | `notification.order.order-events.dlt` | `notification.delivery.shipment-events.dlt` |

- **One listener per topic**, both in consumer group `notification-service`. Each dispatches on the `eventType` header and acknowledges any other type, or a missing header, without doing anything. Two listeners in the same group on the same topic would split its partitions and lose events (see the Delivery spec, *Consuming Order events*).
- **Tolerant reader:** the listener receives the raw record and reads the JSON itself into the type named by the header; unknown fields are ignored. Enum-like values (`reason`) are read as strings, so a new value never breaks deserialisation.
- **Idempotent Consumer:** every handler inserts its `notifications` row keyed by the event's `eventId` (`ON CONFLICT DO NOTHING`). A redelivered event inserts nothing, so it never produces a second email. This is why `eventId` is required here, unlike Delivery and Customer, which are idempotent by state.
- **Offsets** are committed after the listener returned (ack mode `batch`), so only after the database transaction committed.
- **Failures:** a poison message (not JSON, a required field missing or malformed, listed per handler) goes to the dead-letter topic at once. Other failures (e.g. the database briefly down) are retried 4 times with back-off (1 s, 2 s, 4 s, 8 s), then dead-lettered. Retries are blocking, as in Customer and Delivery. Sending an email is not part of the listener, so an SMTP outage never reaches this error handler.
- The source topics may not exist yet: the listeners wait for them and the service still starts (`missing-topics-fatal: false`).

### Recipient read model

Order events carry `customerEmail`. Delivery's `OrderDelivered` does not: customer contact data is not Delivery's responsibility. Notification therefore keeps a local read model:

```
order_recipients (order_id       UUID PRIMARY KEY,
                  customer_email VARCHAR(256) NOT NULL,
                  updated_at     TIMESTAMPTZ  NOT NULL)
```

- Every order-event handler upserts `(orderId, customerEmail)` in the same transaction as its `notifications` row. The email is Order's snapshot taken at placement, so all events of one order carry the same address and the upsert is idempotent.
- `OrderDelivered` stores its notification **without a recipient**; the sender job resolves it from `order_recipients` at send time (EP-NOTIF-JOB-01). The two topics are consumed independently, so after an outage `OrderDelivered` may be read before that order's `OrderShipped`. Resolving at send time turns that lag into a short delay, instead of a listener failure that would end in the dead-letter topic.
- This is the same pattern as Catalog's `stockStatus` (a local copy of another service's data, kept current by events). It keeps Notification free of synchronous calls to Order or Auth, which D1 of the Order spec rejected.

### Notification log

```
notifications (id              UUID PRIMARY KEY,
               event_id        UUID         NOT NULL UNIQUE,   -- dedup key (Idempotent Consumer)
               order_id        UUID         NOT NULL,
               type            VARCHAR(32)  NOT NULL,          -- see below
               recipient       VARCHAR(256),                   -- null until resolved (ORDER_DELIVERED)
               subject         TEXT         NOT NULL,
               body            TEXT         NOT NULL,
               status          VARCHAR(16)  NOT NULL,          -- PENDING, SENT, FAILED
               attempts        INT          NOT NULL DEFAULT 0,
               next_attempt_at TIMESTAMPTZ,                    -- null once SENT or FAILED
               failure_reason  TEXT,
               created_at      TIMESTAMPTZ  NOT NULL,
               sent_at         TIMESTAMPTZ)
```

- `type` is one of `ORDER_PLACED`, `PAYMENT_RECEIVED`, `ORDER_SHIPPED`, `ORDER_DELIVERED`, `ORDER_CANCELLED`, `ORDER_EXPIRED`.
- A handler stores the row as `PENDING` with `next_attempt_at = now`, and the **subject and body already rendered** from the event, so the sender job is generic and the row shows exactly what was sent.
- Index on `(status, next_attempt_at)` for the job's claim.
- **Retention:** a cleanup job (EP-NOTIF-JOB-02) deletes `SENT` and `FAILED` rows, and `order_recipients` rows not updated, older than `NOTIFICATION_RETENTION` (default 30 days). Both tables hold personal data, so they are not kept forever.

### Sending email

```java
// Outbound port
public interface EmailSender {
    void send(EmailMessage message);   // throws EmailSendException (retryable or not)
}
// EmailMessage: to, subject, body (plain text)
```

- **`SmtpEmailSender`** (the only adapter) uses Spring's `JavaMailSender` (`spring-boot-starter-mail`) with connect, read and write timeouts. The sender address is `NOTIFICATION_MAIL_FROM`.
- In Docker Compose and Kubernetes the SMTP server is **[Mailpit](https://mailpit.axllent.org/)** (SMTP port 1025, web UI and REST API on 8025, not exposed outside the cluster). It accepts every message and shows it in its UI, which serves demos, and its API lets end-to-end tests check that an email arrived. A real provider only means different `SPRING_MAIL_*` settings.
- **Failure classification:**
  - *Retryable:* connection failure, timeout, a `4xx` SMTP reply, an authentication failure (logged at `ERROR` as a configuration problem).
  - *Permanent:* a `5xx` reply for the recipient, i.e. the address is rejected → `FAILED` at once with `recipient_rejected`.
- Emails are plain text. Times are formatted in `NOTIFICATION_TIME_ZONE` (default `Europe/Kyiv`) and amounts with two decimals and the currency code (e.g. `1500.00 UAH`).

### Personal data

Email addresses, and the `deliveryDetails` of the fat `OrderPaid` (which Notification ignores), are personal data. They are **never logged**: log lines name `eventId`, `orderId` and the notification `id`, not the address. Email bodies are not logged either.

### Metrics

Following the [monitoring report](../../reports/monitoring-alerting.md):
- Counter `voltstore_notifications_total{type, outcome}` (`outcome` = `sent`, `retry`, `failed`), incremented by the sender job.
- Gauge `voltstore_notifications_pending`, the number of `PENDING` rows, read at scrape time.

A growing pending gauge shows an SMTP outage before customers notice.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | — | Notification's own PostgreSQL database |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | broker address (`kafka:9092` in Compose and Kubernetes) |
| `ORDER_EVENTS_TOPIC` | `order.order-events` | Order's topic |
| `DELIVERY_EVENTS_TOPIC` | `delivery.shipment-events` | Delivery's topic |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | `localhost`, `1025` | SMTP server (`mailpit:1025` in Compose and Kubernetes) |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | empty | SMTP credentials (Mailpit needs none) |
| `NOTIFICATION_MAIL_FROM` | `VoltStore <no-reply@voltstore.com>` | sender address |
| `NOTIFICATION_TIME_ZONE` | `Europe/Kyiv` | time zone of times shown in emails |
| `NOTIFICATION_RETENTION` | `30d` | how long sent/failed notifications and recipients are kept |

Job settings (`notification.sender.interval` 2 s, `batch-size` 20, `lease` 2 min, `backoff` 30 s, 2 min, 10 min, 30 min; `notification.cleanup.interval` 1 h) and SMTP timeouts (connect 5 s, read and write 10 s) are set in `application.yml`.

---

## EP-NOTIF-EVT-01: Consume `OrderPlaced`

**Type:** Event consumer (order-events listener)
**Producer:** Order Service, header `eventType: OrderPlaced`
**Trigger:** A new order is submitted and its stock is reserved; the order is `PENDING_PAYMENT` (EP-ORD-02).

**Payload received:** `{ eventId, orderId, customerId, customerEmail, totalAmount, currency, reservationExpiresAt, timestamp }`. Notification reads `eventId`, `orderId`, `customerEmail`, `totalAmount`, `currency` and `reservationExpiresAt`.

**Steps:**
1. Deserialise and validate (see *Edge cases*).
2. In one transaction: upsert `order_recipients`, and insert a `PENDING` notification of type `ORDER_PLACED` keyed by `eventId`, with:
   - Subject: "VoltStore — Order #`{orderId}` placed, awaiting payment"
   - Body: order number, `totalAmount` and `currency`, and the payment deadline: pay in your account (*My orders*) before `{reservationExpiresAt}`, otherwise the order is cancelled automatically and the items are released.
3. Acknowledge. The sender job sends the email.

**Edge cases:**
- Duplicate `eventId` → nothing inserted, acknowledge.
- Not JSON, or missing `eventId`, `orderId`, `customerEmail` (or an address that is not well-formed), `totalAmount`, `currency` or `reservationExpiresAt` → poison, dead-letter topic.

**External calls:** none (SMTP is the job's)
**Refs:** US-CUST-12, SCN-C14, SCN-S09, N-10

---

## EP-NOTIF-EVT-02: Consume `OrderPaid`

**Type:** Event consumer (order-events listener)
**Producer:** Order Service, header `eventType: OrderPaid`
**Trigger:** Payment succeeded; `PENDING_PAYMENT → PAID` (EP-ORD-03).

**Payload received:** the fat `OrderPaid` from the [Event Catalogue](../services-requirements/events-catalogue.md). Notification reads `eventId`, `orderId`, `customerEmail`, `totalAmount`, `currency` and `paidAt`, and ignores the rest, including `deliveryDetails`.

**Steps:**
1. Deserialise and validate.
2. In one transaction: upsert `order_recipients`, and insert a `PENDING` notification of type `PAYMENT_RECEIVED`:
   - Subject: "VoltStore — Payment received for order #`{orderId}`"
   - Body: a receipt: amount paid (`totalAmount` `currency`) and time of payment (`paidAt`); the parcel is being prepared, and a tracking number follows when it is shipped.
3. Acknowledge.

> The event's `items` carry only `productId` and `quantity`, without titles or prices, so the receipt is not itemised. That would need titles in the event or a call to Catalog; not needed for now.

**Edge cases:**
- Duplicate `eventId` → acknowledge.
- Not JSON, or missing `eventId`, `orderId`, a well-formed `customerEmail`, `totalAmount`, `currency` or `paidAt` → poison.

**External calls:** none
**Refs:** US-CUST-12, SCN-C16, SCN-S09, N-10

---

## EP-NOTIF-EVT-03: Consume `OrderShipped`

**Type:** Event consumer (order-events listener)
**Producer:** Order Service, header `eventType: OrderShipped`
**Trigger:** The inventory worker handed the parcel to the courier; the order is `SHIPPED` (EP-ORD-08).

**Payload received:** `{ eventId, orderId, ttn, customerEmail, timestamp }`

**Steps:**
1. Deserialise and validate.
2. In one transaction: upsert `order_recipients`, and insert a `PENDING` notification of type `ORDER_SHIPPED`:
   - Subject: "VoltStore — Order #`{orderId}` has been shipped"
   - Body: the tracking number (`ttn`) and a note that it can be tracked with the carrier.
3. Acknowledge.

> The event does not name the carrier, so the email does not either.

**Edge cases:**
- Duplicate `eventId` → acknowledge.
- Not JSON, or missing `eventId`, `orderId`, `ttn` or a well-formed `customerEmail` → poison. Order only ships an order that has a TTN (EP-ORD-08), so a missing `ttn` is a broken contract, not a case to work around.

**External calls:** none
**Refs:** US-CUST-12, US-CUST-16, SCN-INV06, SCN-S09, N-10

---

## EP-NOTIF-EVT-04: Consume `OrderDelivered`

**Type:** Event consumer (delivery-events listener)
**Producer:** **Delivery Service**, topic `delivery.shipment-events`, header `eventType: OrderDelivered`
**Trigger:** The carrier reported that the parcel reached the customer (EP-DEL-04); emitted once per shipment.

**Payload received:** `{ eventId, orderId, ttn, timestamp }`. There is no `customerEmail`; see *Recipient read model*.

**Steps:**
1. Deserialise and validate.
2. Insert a `PENDING` notification of type `ORDER_DELIVERED` with `recipient = null`:
   - Subject: "VoltStore — Order #`{orderId}` delivered"
   - Body: delivery confirmation with the `ttn`, and a thank-you.
3. Acknowledge. The sender job resolves the recipient from `order_recipients` and then sends.

**Edge cases:**
- Duplicate `eventId` → acknowledge.
- Not JSON, or missing `eventId`, `orderId` or `ttn` → poison, `notification.delivery.shipment-events.dlt`.
- No recipient known yet (the order events are still behind) → handled by the job's back-off; the listener does not wait for it.

**External calls:** none
**Refs:** US-CUST-12, US-CUST-16, SCN-S08, SCN-S09, N-10

---

## EP-NOTIF-EVT-05: Consume `OrderCancelled`

**Type:** Event consumer (order-events listener)
**Producer:** Order Service, header `eventType: OrderCancelled`
**Trigger:** The cancellation saga finished (`→ CANCELLED`, EP-ORD-06 / EP-ORD-JOB-02), or the payment window elapsed (`→ EXPIRED`, EP-ORD-JOB-01).

**Payload received:** `{ eventId, orderId, customerId, customerEmail, reason, timestamp }`, where `reason` is `"CUSTOMER_CANCELLED"` or `"EXPIRED"`.

**Steps:**
1. Deserialise and validate.
2. In one transaction: upsert `order_recipients`, and insert a `PENDING` notification chosen by `reason`:
   - `CUSTOMER_CANCELLED` → type `ORDER_CANCELLED`
     - Subject: "VoltStore — Order #`{orderId}` cancelled"
     - Body: the cancellation is confirmed; *if the order was already paid*, the refund has been initiated and may take a few business days to arrive (decision D3).
   - `EXPIRED` → type `ORDER_EXPIRED`
     - Subject: "VoltStore — Order #`{orderId}` expired, payment deadline missed"
     - Body: the order was not paid in time; the reserved items were released and nothing was charged; the customer is welcome to order again.
   - Any other value → type `ORDER_CANCELLED` with the `CUSTOMER_CANCELLED` text, and a warning is logged.
3. Acknowledge.

> A customer can cancel both an unpaid order (`PENDING_PAYMENT`, no refund) and a paid one (`PAID` / `PENDING_SHIPPING`, refunded by the saga). Both arrive as `CUSTOMER_CANCELLED`, and the event does not say whether a refund was made, so the text covers both cases (D3).

**Edge cases:**
- Duplicate `eventId` → acknowledge.
- Not JSON, or missing `eventId`, `orderId`, `reason` or a well-formed `customerEmail` → poison.

**External calls:** none
**Refs:** US-CUST-12, US-CUST-13, SCN-S06, SCN-S07, SCN-S09, N-10

---

## EP-NOTIF-JOB-01: Send Pending Notifications

**Type:** Scheduled job (every `notification.sender.interval`, default 2 s)

**Steps:**
1. **Claim** due rows: `status = PENDING` and `next_attempt_at <= now`, oldest first, up to the batch size (default 20), with `FOR UPDATE SKIP LOCKED`. The claim moves `next_attempt_at` forward by a lease (default 2 min, far longer than the SMTP timeouts), so another replica does not pick the same row while it is being sent. The claim transaction commits before any SMTP call.
2. For each claimed row, outside any transaction:
   1. If `recipient` is null, look it up in `order_recipients` by `order_id`. Found → store it on the row. Not found → a retryable failure with `recipient_unknown`.
   2. `EmailSender.send(recipient, subject, body)`.
3. Apply the result in a new transaction:
   - **Sent** → `SENT`, `sent_at = now`, `next_attempt_at = null`.
   - **Retryable failure** → `attempts + 1`, `failure_reason` = the latest error, `next_attempt_at = now + ` the next back-off (30 s, 2 min, 10 min, 30 min). After the fifth failed attempt → `FAILED`, logged at `ERROR` once.
   - **Permanent failure** (`recipient_rejected`) → `FAILED` at once, logged at `WARN`.
4. One failing row never stops the run.

> **At-least-once sending.** If the instance stops after the SMTP server accepted a message but before the row is marked `SENT`, the lease expires and the email is sent again. SMTP has no idempotency key, so this small window cannot be closed; a rare duplicate email is accepted. The opposite (a lost email) cannot happen, because the row is marked `SENT` only after the server accepted the message.

> **Why a job and not sending inside the listener:** an SMTP outage would block the partition for every later event and could exceed the consumer's poll interval, and retries kept in memory would be lost on a restart. With the job, the listener commits in milliseconds, retries survive restarts, and the pending rows are visible in a metric.

**External calls:** SMTP, through `EmailSender` (sync, per row)
**Refs:** SCN-S09, N-10, D2

---

## EP-NOTIF-JOB-02: Clean Up Old Notifications

**Type:** Scheduled job (every `notification.cleanup.interval`, default 1 h)

**Steps:**
1. Delete `notifications` with `status` in `{ SENT, FAILED }` and `created_at` older than `NOTIFICATION_RETENTION`.
2. Delete `order_recipients` with `updated_at` older than `NOTIFICATION_RETENTION`.

> Deleting a notification also deletes its dedup key. A redelivery of a 30-day-old event is not expected: Kafka redelivers within minutes, and Order's and Delivery's outboxes publish within seconds. Several replicas need no claim: deleting twice is harmless.

---

## Decisions

Agreed on 2026-10-18:

| # | Question | Decision | Consequence |
|---|---|---|---|
| D1 | `OrderDelivered` comes from Delivery without `customerEmail`. How does Notification find the recipient? | A local read model `order_recipients (orderId → customerEmail)`, filled from the four order events. The sender job resolves the recipient at send time. | Notification needs a database (needed for D2 anyway). No change in Order or Delivery. Rejected: Order publishing its own "delivered" event with the email (a new event and Order code changes); Delivery copying the email from `OrderPaid` (against the Delivery spec's decision that contact data is not its concern); a lookup in Order or Auth (synchronous coupling, rejected by D1 of the Order spec). |
| D2 | How are duplicates and email-provider failures handled? | Listeners store a `notifications` row keyed by `eventId` (Idempotent Consumer); a scheduled job sends with back-off and marks rows `SENT` / `FAILED`. Poison events go to Notification's dead-letter topics. | A provider outage never blocks Kafka; retries survive restarts; a rare duplicate after a crash is accepted. Rejected: sending inside the listener with `processed_events` (an outage stalls the partition); no deduplication (a duplicate email on every redelivery). |
| D3 | `CUSTOMER_CANCELLED` is used for unpaid and paid orders. How does the email tell whether a refund was made? | Neutral copy: "if the order was already paid, the refund has been initiated". | No event contract change. Rejected for now: a `refunded` flag on `OrderCancelled` (more precise, but changes Order); inferring it from whether `OrderPaid` was seen (wrong under consumer lag). |
| D4 | Which email provider? | An `EmailSender` port with an SMTP adapter (Spring Mail); Mailpit in Compose and Kubernetes. | Real emails can be seen in the Mailpit UI and checked by e2e tests; a real provider needs only SMTP settings. Rejected: a logging-only mock (nothing to show or assert end to end). |

---

## Changes from the First Spec Version

| Area | First version | Now | Why |
|---|---|---|---|
| `OrderDelivered` | from Order, payload `{ orderId, ttn, customerEmail }` | from **Delivery** on `delivery.shipment-events`, `{ eventId, orderId, ttn, timestamp }`; the recipient comes from the local read model | aligned with the Delivery spec (2026-10-15); D1 |
| Retries and dead letters | deferred; email "fire-and-forget" | notification log + sender job with back-off; poison events to `notification.*.dlt`; listener retries 4× | D2; same conventions as Customer and Delivery |
| Duplicates | not addressed | Idempotent Consumer on `eventId` | events are delivered at least once |
| Bad or missing `customerEmail` | log and acknowledge | poison message, dead-letter topic | Order always sends the account email (Order spec D1); a missing one is a broken contract, and the DLT keeps it visible |
| `OrderPlaced` email | "Order confirmed", reservation "expires in 15 minutes", payment link | "placed, awaiting payment", deadline from `reservationExpiresAt`, pay under *My orders* | the order is not confirmed until paid; the window is configurable in Order; there is no frontend URL to link |
| `OrderPaid` email | payment confirmation | receipt with amount and `paidAt` | the event carries them; the catalogue calls it a receipt |
| `OrderShipped` email | TTN and carrier name; fallback when `ttn` is null | TTN only; a missing `ttn` is poison | the event has no carrier; Order never ships without a TTN |
| `OrderCancelled` email | `CUSTOMER_CANCELLED` = "refund initiated" | neutral "if already paid, refund initiated" | unpaid orders are cancelled with the same reason; D3 |
| Payloads | without `eventId` / `timestamp` | as in the Event Catalogue | Order/Delivery event conventions |
| Infrastructure | email provider "SMTP / third-party API" | `EmailSender` port, SMTP adapter, Mailpit; own PostgreSQL; metrics | D4; observability like the other services |
| Missing sections | — | security, consuming events, configuration, personal data | same structure as the other specs |

### Other documents updated with this revision (2026-10-18)

- [events-catalogue.md](../services-requirements/events-catalogue.md): `OrderDelivered` note; the recipient comes from Notification's read model.
- [entities.md](../entities/entities.md) §8: Notification's technical tables.
- [system-scenarios.md](../scenarios/system-scenarios.md) SCN-S09: email texts and the error handling summary.
- [user-stories-review-notes.md](../user-stories/user-stories-review-notes.md) N-10: resolved.

---

## Implementation Status

| Part | Status |
|---|---|
| Module | Scaffold only (`notification-service/`: Spring Boot 4.1.1, Java 25, starters for Kafka, Web MVC, Actuator, Validation; Testcontainers Kafka). Not yet added: Data JPA + PostgreSQL driver, Flyway, Spring Security, `spring-boot-starter-mail`, `micrometer-registry-prometheus`, Testcontainers PostgreSQL |
| Tasks | Backlog written 2026-10-18: [tasks 01–13](../../tasks/notification-service/), none started |
| Compose / Kubernetes | No entries yet; Mailpit to be added with them |
