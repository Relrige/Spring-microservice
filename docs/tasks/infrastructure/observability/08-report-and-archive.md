---
status: DONE
service: infrastructure
---
# Shared Report and Submission Archive

## Context
The assignment is submitted as one archive: configurations, manifests, the shared report and the investigation video or screenshots.
*References:* [Overview](00-overview.md), [Monitoring report (structure to follow)](../../../reports/monitoring-alerting.md)

## Acceptance Criteria
- [x] Report `docs/reports/tracing-logging.md`:
  - [x] architecture of the interaction (diagram: services, gateway, Kafka, collector agent/gateway, Tempo, Loki, Grafana) and the decisions from the overview with their trade-offs;
  - [x] the collector configuration explained block by block (receivers, processors, tail-sampling policies, exporters, pipelines);
  - [x] how W3C Trace Context travels: HTTP header, the outbox column, the Kafka header, with a real `traceparent` value decoded;
  - [x] the JSON log format with a real line from two services sharing a `traceId`;
  - [x] the e2e trace and the diagnostic loop with screenshots (task 07);
  - [x] limitations (single sampler, `emptyDir` storage, minikube memory) and what production would change.
- [x] Archive contents: `k8s/` manifests for the new components (Secret templates only, never real secrets), collector/Tempo/Loki/Grafana configs, changed `application.yml` files, the report, the screenshots/video.
- [x] Archive built by a documented command; checked that no `secret.yaml` or `.env` is inside.
- [x] Task statuses in this folder updated to `DONE`.

## Implementation Notes
- Report: [`docs/reports/tracing-logging.md`](../../../reports/tracing-logging.md), with the structure of the monitoring report. Screenshots and the two scripts are in `docs/reports/tracing-logging/`.
- Archive: `sh docs/reports/tracing-logging/build-archive.sh` (from anywhere in the repository) writes `tracing-logging-submission.zip` at the repository root (`*.zip` is git-ignored).
  - It packs **committed files only** (`git archive HEAD`), so ignored files such as `k8s/**/secret.yaml` and `.env` cannot get in, and uncommitted edits are not packed.
  - It then lists the archive and deletes it, with an error, if a `secret.yaml` or `.env` is inside anyway.
  - Contents: the report and its folder, this task folder, `k8s/observability/`, the network policies, every `k8s/*/app-config.yaml`, and from the services `pom.xml`, `application.yml`, the traceparent migrations, `TracingConfig`, `ClientsConfiguration`, the outbox classes and `KafkaConfig`.
- The screenshots are limited to the desktop app's browser pane, about 800 px wide; Grafana's kiosk mode and collapsed panels keep them readable.

## Verification (2026-10-25)
- Archive built from commit `b749fcc`: 112 files, among them the 9 screenshots and both scripts. `unzip -Z1 … | grep -i -E 'secret|\.env'` finds only `k8s/observability/grafana-secret-template.yaml` (placeholders only).
- Every task in this folder, 00 to 08, has `status: DONE`.
