---
status: DONE
service: notification-service
---
# Actuator-Only Security Chain

## Context
Notification has no business endpoints and no callers: neither the gateway nor other services route to it. Like every other service, it still needs an explicit allowlist with `denyAll()` for everything else, so that a later controller or actuator endpoint is never exposed by accident. Only the probes and Prometheus scraping are open (see the [monitoring report](../../reports/monitoring-alerting.md)).

Unlike the other services it needs **no** trusted-header or internal-token filter. No request carries user identity, and Notification makes no outgoing HTTP calls.
*References:* [Notification Spec — Security and routing](../../design/services-specs/notification-service-spec.md), [Security Model](../../design/services-requirements/security-model.md), [Customer security task](../customer-service/03-trusted-header-security.md)

## Acceptance Criteria
- [x] `spring-boot-starter-security` added; `SecurityConfig` with a stateless chain (no session, no CSRF, no form login, no HTTP basic):
  - `/actuator/health/**` → `permitAll()`
  - `/actuator/prometheus` → `permitAll()` (the endpoint itself is exposed in task 11)
  - anything else → `denyAll()`
- [x] No default generated user or password is logged at startup.
- [x] Denied requests answer with the shared `ProblemDetail` shape (`403`, `service: notification-service`), using the same handler classes as Customer, where they apply.
- [x] Tests: health liveness and readiness answer `200` without credentials; any other path (e.g. `/`, `/actuator/env`, `/notifications`) answers `403`.

## Technical Notes / Constraints
- Keep the chain small and do not copy unused filters. In the task notes, explain why this service is the exception to the trusted-header pattern.
- Keep `management.endpoints.web.exposure.include` limited to `health` (and `prometheus` from task 11).

## Implementation Notes (2026-10-18)
- `spring-boot-starter-security` (+ `-test`). `security/SecurityConfig` is a stateless chain with no CSRF, form login, HTTP basic, logout or request cache:
  - `/actuator/health/**` and `/actuator/prometheus` → `permitAll()`. The pattern also matches `/actuator/health` itself, as in the other services.
  - everything else → `denyAll()`.
- **Why no trusted-header or internal-token filter (the exception to the pattern):** Notification has no callers. The gateway does not route to it and no service calls it, so no request carries a user identity or `X-Internal-Token`. Its only outgoing connection is SMTP, so it sends no headers either. A filter would only be dead code that suggests a route exists.
- `security/SecurityErrorHandlers`: the same problem body as Customer's handler class. Every caller is anonymous, so Spring Security reports a denied path through the entry point; the entry point therefore also answers **403** ("Access denied"), not 401, because there is no way to authenticate. No `GlobalExceptionHandler` was copied: there are no controllers whose exceptions it would map.
- `UserDetailsServiceAutoConfiguration` is excluded, so there is no default user and no generated password in the log.
- `management.endpoints.web.exposure.include` is still `health`; `prometheus` is added in task 11.
- **Verified (2026-10-18):** `SecurityConfigTest` (full context on Testcontainers, MockMvc):
  - health, liveness and readiness answer 200 `UP` without credentials;
  - `/`, `/actuator`, `/actuator/env`, `/actuator/beans`, `/notifications` and `/notifications/123` answer the 403 problem with `service: notification-service`;
  - Basic or trusted headers don't help;
  - no `UserDetailsService` bean exists.
