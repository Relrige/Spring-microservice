---
status: TODO
service: infrastructure
---
# Grafana: from a Span to Its Logs in One Click

## Context
In Grafana, a span in Tempo must open the matching logs in Loki with one click (and, ideally, a log line must open its trace).
*References:* [Overview](00-overview.md), [Task 05](05-json-logs-to-loki.md)

## Acceptance Criteria
- [ ] Tempo data source provisioned with *trace to logs* (`tracesToLogsV2`): target = Loki data source, filter by trace id, mapping of the span's `service.name` to the Loki service label, a small time shift around the span.
- [ ] Loki data source provisioned with a *derived field* on `traceId` that links to the Tempo data source (log → trace).
- [ ] Optional: Tempo → Prometheus link (trace to metrics) and Tempo's service graph; nice for the report, not required.
- [ ] Data sources come from files in a ConfigMap (no manual clicking that disappears with the pod).
- [ ] Check: open a trace of the e2e scenario, click *Logs for this span* on an Order span and on a Kafka consumer span; both show only that request's lines.
