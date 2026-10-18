---
status: TODO
service: notification-service
---
# Metrics: Sent and Failed Notifications, Pending Backlog

## Context
Every service exposes `/actuator/prometheus` and is scraped automatically through pod annotations ([monitoring report](../../reports/monitoring-alerting.md)). A growing count of pending notifications is the first sign of an SMTP outage, earlier than customers noticing missing emails, so Notification adds two custom meters.
*References:* [Notification Spec — Metrics](../../design/services-specs/notification-service-spec.md), [Monitoring task](../infrastructure/monitoring-prometheus-alertmanager.md)

## Acceptance Criteria
- [ ] `micrometer-registry-prometheus` (runtime), `management.endpoints.web.exposure.include=health,prometheus`, common tag `application`, as in the other services.
- [ ] Counter `voltstore_notifications_total{type, outcome}` with `outcome` = `sent`, `retry` or `failed`, incremented by the sender job (task 09).
- [ ] Gauge `voltstore_notifications_pending`: the number of `PENDING` rows, read from the database at scrape time (like Catalog's `voltstore_outbox_pending_events`).
- [ ] Test: after one sent and one failed notification, `/actuator/prometheus` shows both counter series and the gauge.

## Technical Notes / Constraints
- Keep the label values bounded. `type` and `outcome` are enums; never use `orderId` or a failure message as a label.
- An alert rule (e.g. pending > 0 for 15 min) is optional. If you add one, add it in `k8s/monitoring/prometheus-config.yaml` and the monitoring report, not here.
