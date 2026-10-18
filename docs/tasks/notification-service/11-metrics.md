---
status: DONE
service: notification-service
---
# Metrics: Sent and Failed Notifications, Pending Backlog

## Context
Every service exposes `/actuator/prometheus` and is scraped automatically through pod annotations ([monitoring report](../../reports/monitoring-alerting.md)). A growing count of pending notifications is the first sign of an SMTP outage, earlier than customers noticing missing emails, so Notification adds two custom meters.
*References:* [Notification Spec — Metrics](../../design/services-specs/notification-service-spec.md), [Monitoring task](../infrastructure/monitoring-prometheus-alertmanager.md)

## Acceptance Criteria
- [x] `micrometer-registry-prometheus` (runtime), `management.endpoints.web.exposure.include=health,prometheus`, common tag `application`, as in the other services.
- [x] Counter `voltstore_notifications_total{type, outcome}` with `outcome` = `sent`, `retry` or `failed`, incremented by the sender job (task 09).
- [x] Gauge `voltstore_notifications_pending`: the number of `PENDING` rows, read from the database at scrape time (like Catalog's `voltstore_outbox_pending_events`).
- [x] Test: after one sent and one failed notification, `/actuator/prometheus` shows both counter series and the gauge.

## Technical Notes / Constraints
- Keep the label values bounded. `type` and `outcome` are enums; never use `orderId` or a failure message as a label.
- An alert rule (e.g. pending > 0 for 15 min) is optional. If you add one, add it in `k8s/monitoring/prometheus-config.yaml` and the monitoring report, not here.

## Implementation Notes (2026-10-18)
- `micrometer-registry-prometheus` (runtime). `management.endpoints.web.exposure.include: health,prometheus`. Common tag `application: notification-service`. `/actuator/prometheus` was already `permitAll` (task 03).
- `metrics/NotificationMetrics`:
  - counter `voltstore.notifications` → `voltstore_notifications_total{type, outcome}`, with `outcome` ∈ `sent`, `retry`, `failed` (enum `Outcome`);
  - gauge `voltstore.notifications.pending` → `voltstore_notifications_pending`, read at scrape time with `countByStatus(PENDING)`, like Catalog's outbox gauge. If the database is down, the value is NaN rather than a failed scrape.
- **Job wiring:** `NotificationSenderJob.apply` now returns the stored `Outcome` (or `null` when the result was discarded). The counter is incremented only after that transaction committed, so a rolled-back result is never counted. A row given up after 5 attempts counts as 4 × `retry` + 1 × `failed`.
- Label values are bounded (two enums). No order ID, address or failure reason is used as a label; the test checks that neither the address nor the reason appears in the scrape.
- **Alert rule not added** (optional in the task). `voltstore_notifications_pending > 0` for 15 min would be the natural one, in `k8s/monitoring/prometheus-config.yaml` and the monitoring report. It is left as a follow-up.
- **Verified (2026-10-18):** `NotificationMetricsIntegrationTest` (`@AutoConfigureMetrics`, since Boot tests switch metrics export off; PostgreSQL + Kafka + Mailpit):
  - one sent and one permanently failed notification (an unparsable address, because Mailpit accepts everything) plus one not yet due;
  - an anonymous `GET /actuator/prometheus` shows `outcome="sent",type="ORDER_PLACED"} 1.0`, `outcome="failed",type="ORDER_SHIPPED"} 1.0` and `voltstore_notifications_pending{…} 1.0`.
