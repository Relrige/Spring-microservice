---
status: TODO
service: notification-service
---
# Actuator-Only Security Chain

## Context
Notification has no business endpoints and no callers: neither the gateway nor other services route to it. Like every other service, it still needs an explicit allowlist with `denyAll()` for everything else, so that a later controller or actuator endpoint is never exposed by accident. Only the probes and Prometheus scraping are open (see the [monitoring report](../../reports/monitoring-alerting.md)).

Unlike the other services it needs **no** trusted-header or internal-token filter. No request carries user identity, and Notification makes no outgoing HTTP calls.
*References:* [Notification Spec — Security and routing](../../design/services-specs/notification-service-spec.md), [Security Model](../../design/services-requirements/security-model.md), [Customer security task](../customer-service/03-trusted-header-security.md)

## Acceptance Criteria
- [ ] `spring-boot-starter-security` added; `SecurityConfig` with a stateless chain (no session, no CSRF, no form login, no HTTP basic):
  - `/actuator/health/**` → `permitAll()`
  - `/actuator/prometheus` → `permitAll()` (the endpoint itself is exposed in task 11)
  - anything else → `denyAll()`
- [ ] No default generated user or password is logged at startup.
- [ ] Denied requests answer with the shared `ProblemDetail` shape (`403`, `service: notification-service`), using the same handler classes as Customer, where they apply.
- [ ] Tests: health liveness and readiness answer `200` without credentials; any other path (e.g. `/`, `/actuator/env`, `/notifications`) answers `403`.

## Technical Notes / Constraints
- Keep the chain small and do not copy unused filters. In the task notes, explain why this service is the exception to the trusted-header pattern.
- Keep `management.endpoints.web.exposure.include` limited to `health` (and `prometheus` from task 11).
