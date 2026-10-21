---
status: TODO
service: infrastructure
---
# Shared Report and Submission Archive

## Context
The assignment is submitted as one archive: configurations, manifests, the shared report and the investigation video or screenshots.
*References:* [Overview](00-overview.md), [Monitoring report (structure to follow)](../../../reports/monitoring-alerting.md)

## Acceptance Criteria
- [ ] Report `docs/reports/tracing-logging.md`:
  - [ ] architecture of the interaction (diagram: services, gateway, Kafka, collector agent/gateway, Tempo, Loki, Grafana) and the decisions from the overview with their trade-offs;
  - [ ] the collector configuration explained block by block (receivers, processors, tail-sampling policies, exporters, pipelines);
  - [ ] how W3C Trace Context travels: HTTP header, the outbox column, the Kafka header, with a real `traceparent` value decoded;
  - [ ] the JSON log format with a real line from two services sharing a `traceId`;
  - [ ] the e2e trace and the diagnostic loop with screenshots (task 07);
  - [ ] limitations (single sampler, `emptyDir` storage, minikube memory) and what production would change.
- [ ] Archive contents: `k8s/` manifests for the new components (Secret templates only, never real secrets), collector/Tempo/Loki/Grafana configs, changed `application.yml` files, the report, the screenshots/video.
- [ ] Archive built by a documented command; checked that no `secret.yaml` or `.env` is inside.
- [ ] Task statuses in this folder updated to `DONE`.
