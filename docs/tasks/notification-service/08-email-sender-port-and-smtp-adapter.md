---
status: DONE
service: notification-service
---
# `EmailSender` Port and SMTP Adapter (Mailpit)

## Context
Email goes out through an outbound port, `EmailSender`, in the same way as Delivery's `CarrierGateway` and Payment's `PaymentGateway`: the service layer knows only the port, and the adapter knows the protocol. The only adapter is SMTP via Spring's `JavaMailSender`. In Compose and Kubernetes it talks to **Mailpit**, a local SMTP server with a web UI and REST API that accepts every message (D4). A real provider later needs only different `SPRING_MAIL_*` settings.

The job (task 09) must know whether a failure is worth retrying. So the adapter converts mail exceptions into one exception type that says whether the failure is retryable.
*References:* [Notification Spec — Sending email, D4](../../design/services-specs/notification-service-spec.md), [Delivery carrier port task](../delivery-service/07-carrier-gateway-port-and-mock.md)

## Acceptance Criteria
- [x] `spring-boot-starter-mail` added. Port `EmailSender.send(EmailMessage)`, where `EmailMessage(to, subject, body)` is plain text. `EmailSendException(reason, retryable)`.
- [x] `SmtpEmailSender` sends with the sender address `NOTIFICATION_MAIL_FROM` (default `VoltStore <no-reply@voltstore.com>`) and UTF-8 encoding.
- [x] SMTP timeouts set in `application.yml`: connect 5 s, read 10 s, write 10 s (`spring.mail.properties.mail.smtp.*`).
- [x] Failure classification:
  - connection failure, timeout, a `4xx` reply → retryable `smtp_unavailable: …` / `smtp_temporary: …`;
  - authentication failure → retryable, logged at `ERROR` as a configuration problem;
  - a `5xx` reply for the recipient → not retryable, `recipient_rejected`.
- [x] `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` (defaults `localhost`, `1025`), `SPRING_MAIL_USERNAME` / `_PASSWORD` (empty) and `NOTIFICATION_MAIL_FROM` documented in `.env.example`.
- [x] Integration tests with a Mailpit container (`axllent/mailpit`, Testcontainers `GenericContainer`, SMTP 1025 and API 8025):
  - a sent message arrives, checked through Mailpit's REST API: recipient, sender, subject, UTF-8 body (e.g. "—" and Cyrillic);
  - with the container stopped or an unused port → a retryable exception within the timeout.
- [x] Unit tests for the classification, using mocked `MailException` / `SMTPAddressFailedException` cases.

## Technical Notes / Constraints
- Mailpit's REST API is useful for both this test and the end-to-end check (task 13). Pin an exact image tag, as the other containers do.
- An alternative for unit-level tests is GreenMail (an in-JVM SMTP server). If you consider it, note the trade-off against using the same server as in Compose.
- Do not log the recipient or the body, not even at `DEBUG`.

## Implementation Notes (2026-10-18)
- **Port:**
  - `email/EmailSender.send(EmailMessage)`;
  - `EmailMessage(to, subject, body)`, whose `toString` leaves out the address and body;
  - `EmailSendException(reason, retryable)`, where `reason` is the text stored as `failure_reason` and never contains the address.
- **Adapter `SmtpEmailSender`:**
  - `MimeMessageHelper` with UTF-8, plain text;
  - sender parsed once at startup from `notification.mail.from` (← `NOTIFICATION_MAIL_FROM`, default `VoltStore <no-reply@voltstore.com>`), so a broken value fails fast;
  - recipient parsed strictly.
- **Dependencies:** `spring-boot-starter-mail`, plus `org.eclipse.angus:angus-mail` at compile scope (version from Boot). The starter has it only at runtime, but the classification reads SMTP reply codes from its exception classes.
- **Classification** (in the class Javadoc). Server replies are reduced to their code, because their text often repeats the address:
  - `5xx` on `RCPT TO` (`SMTPAddressFailedException`) → permanent `recipient_rejected: 550`;
  - `4xx` on `RCPT TO` → retryable `smtp_temporary: 451`;
  - other SMTP replies → retryable `smtp_temporary: <code>` / `smtp_rejected: <code>`. The latter is e.g. a rejected sender: a configuration problem that can be fixed, and the spec makes only a rejected *recipient* permanent;
  - authentication failure → retryable `smtp_auth_failed`, logged at `ERROR` as a configuration problem;
  - connection refused, timeout, unknown host → retryable `smtp_unavailable: <exception>: <message>`;
  - **addition:** an address that cannot be parsed → permanent `recipient_invalid`. It would fail the same way on every attempt.
- **`application.yml`:**
  - `spring.mail.host/port` default `localhost:1025`, UTF-8;
  - timeouts `mail.smtp.connectiontimeout` 5000, `timeout` 10000, `writetimeout` 10000;
  - `management.health.mail.enabled: false`: an SMTP outage must not mark the service unhealthy, the pending gauge shows it instead (task 11).
- `.env.example` documents `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` and `NOTIFICATION_MAIL_FROM`. An empty username means no authentication (`JavaMailSenderImpl` treats `""` as unset).
- **Mailpit vs. GreenMail:** Mailpit (`axllent/mailpit:v1.27.10`, Testcontainers `GenericContainer`) is the same server as in Compose and Kubernetes. Its REST API is reused by the job tests (task 09) and the e2e checklist (task 13). GreenMail would run in the JVM without Docker and start faster, but every integration test here already needs Docker for PostgreSQL and Kafka, and a second SMTP implementation could behave differently from the one the demos use. The helper is `support/MailpitContainer`: `messages()`, `text(id)`, `clear()`, and a `Config` that points `spring.mail.*` at the container.
- **Verified (2026-10-18):**
  - `SmtpEmailSenderClassificationTest` (unit, the exception shapes `JavaMailSenderImpl` produces): 550/451 on the recipient, 421/554 on `DATA`, connection refused, read timeout, authentication failure;
  - `SmtpEmailSenderMailpitTest`:
    - a message arrives in Mailpit with recipient, sender address and name, subject with "—", and a Cyrillic body;
    - an unused port → retryable `smtp_unavailable` at once;
    - a server that accepts but never greets → retryable within the 1 s test timeout (< 5 s);
    - an unparsable address → permanent `recipient_invalid`, nothing sent.
