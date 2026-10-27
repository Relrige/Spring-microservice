---
status: DONE
service: infrastructure
---
# Grafana: from a Span to Its Logs in One Click

## Context
In Grafana, a span in Tempo must open the matching logs in Loki with one click (and, ideally, a log line must open its trace).
*References:* [Overview](00-overview.md), [Task 05](05-json-logs-to-loki.md)

## Acceptance Criteria
- [x] Tempo data source provisioned with *trace to logs* (`tracesToLogsV2`): target = Loki data source, filter by trace id, mapping of the span's `service.name` to the Loki service label, a small time shift around the span.
- [x] Loki data source provisioned with a *derived field* on `traceId` that links to the Tempo data source (log → trace).
- [ ] Optional: Tempo → Prometheus link (trace to metrics) and Tempo's service graph; nice for the report, not required.
- [x] Data sources come from files in a ConfigMap (no manual clicking that disappears with the pod).
- [x] Check: open a trace of the e2e scenario, click *Logs for this span* on an Order span and on a Kafka consumer span; both show only that request's lines.

## Implementation Notes
- `k8s/observability/grafana-datasources.yaml` (provisioned from the ConfigMap at startup):
  - **Tempo → Loki** (`tracesToLogsV2`): a custom query `{${__tags}} | trace_id="${__trace.traceId}"`. The tag mapping turns the span's `service.name` into `service_name="…"`, and the trace id is matched on Loki's structured metadata `trace_id` (set by the agent, task 05), so no line is parsed. The span time is widened by 1 minute on each side.
  - **Loki → Tempo**: a derived field of type `label` on the structured metadata `trace_id`, linking to the Tempo data source ("Open trace in Tempo"). It reads `trace_id`, not the `traceId` JSON field, so it works without `| json`; lines outside a trace get no link.
  - The Tempo search and the node graph are switched on.
- Grafana expands environment variables in provisioning files, so every literal `$` is written `$$`. The Grafana API returned the stored links with `${__tags}`, `${__trace.traceId}` and `${__value.raw}` intact.
- Order logged nothing at INFO on place or pay, so *Logs for this span* on an Order span was empty. Two log lines were added (ids and item count only, no personal data):
  - `Order <id> placed with <n> items; awaiting payment`;
  - `Order <id> paid`.
- Optional parts skipped: trace to metrics and the service graph (the service graph needs Tempo's metrics generator).

## Verification (minikube, 2026-10-25)
- The query Grafana builds for *Logs for this span* was replayed through Grafana's own query API (`/api/ds/query`, Loki data source, span time ±1 min) for spans of one kept pay trace:
  - Order `http post /orders/{orderId}/pay` (server): 1 line, `Order … paid`;
  - Delivery, Notification and Customer `order.order-events process` (Kafka consumers): 1 line each (shipment created, notification recorded, cart cleaned);
  - every returned line belongs to that trace.
- About 75 % of successful traces are dropped by the tail sampler (task 04); it took six checkouts to get a kept one. The e2e demo (task 07) should use a kept trace or an error trace.
- Not clicked in the browser UI: the agent did not sign in to Grafana. The links are verified through the stored configuration and the replayed queries; the screenshots for the report come with task 07.
- Order tests: 374, all green.
