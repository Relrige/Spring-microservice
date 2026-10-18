---
status: TODO
service: notification-service
---
# `EmailSender` Port and SMTP Adapter (Mailpit)

## Context
Email goes out through an outbound port, `EmailSender`, in the same way as Delivery's `CarrierGateway` and Payment's `PaymentGateway`: the service layer knows only the port, and the adapter knows the protocol. The only adapter is SMTP via Spring's `JavaMailSender`. In Compose and Kubernetes it talks to **Mailpit**, a local SMTP server with a web UI and REST API that accepts every message (D4). A real provider later needs only different `SPRING_MAIL_*` settings.

The job (task 09) must know whether a failure is worth retrying. So the adapter converts mail exceptions into one exception type that says whether the failure is retryable.
*References:* [Notification Spec — Sending email, D4](../../design/services-specs/notification-service-spec.md), [Delivery carrier port task](../delivery-service/07-carrier-gateway-port-and-mock.md)

## Acceptance Criteria
- [ ] `spring-boot-starter-mail` added. Port `EmailSender.send(EmailMessage)`, where `EmailMessage(to, subject, body)` is plain text. `EmailSendException(reason, retryable)`.
- [ ] `SmtpEmailSender` sends with the sender address `NOTIFICATION_MAIL_FROM` (default `VoltStore <no-reply@voltstore.com>`) and UTF-8 encoding.
- [ ] SMTP timeouts set in `application.yml`: connect 5 s, read 10 s, write 10 s (`spring.mail.properties.mail.smtp.*`).
- [ ] Failure classification:
  - connection failure, timeout, a `4xx` reply → retryable `smtp_unavailable: …` / `smtp_temporary: …`;
  - authentication failure → retryable, logged at `ERROR` as a configuration problem;
  - a `5xx` reply for the recipient → not retryable, `recipient_rejected`.
- [ ] `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` (defaults `localhost`, `1025`), `SPRING_MAIL_USERNAME` / `_PASSWORD` (empty) and `NOTIFICATION_MAIL_FROM` documented in `.env.example`.
- [ ] Integration tests with a Mailpit container (`axllent/mailpit`, Testcontainers `GenericContainer`, SMTP 1025 and API 8025):
  - a sent message arrives, checked through Mailpit's REST API: recipient, sender, subject, UTF-8 body (e.g. "—" and Cyrillic);
  - with the container stopped or an unused port → a retryable exception within the timeout.
- [ ] Unit tests for the classification, using mocked `MailException` / `SMTPAddressFailedException` cases.

## Technical Notes / Constraints
- Mailpit's REST API is useful for both this test and the end-to-end check (task 13). Pin an exact image tag, as the other containers do.
- An alternative for unit-level tests is GreenMail (an in-JVM SMTP server). If you consider it, note the trade-off against using the same server as in Compose.
- Do not log the recipient or the body, not even at `DEBUG`.
