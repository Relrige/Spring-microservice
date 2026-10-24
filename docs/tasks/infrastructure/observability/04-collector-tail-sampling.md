---
status: TODO
service: infrastructure
---
# Collector Pipelines: OTLP Receiver, Tail Sampling, Export to Tempo

## Context
The collector receives all spans over OTLP, decides per complete trace which ones to keep (every trace with an error must be kept), and exports the kept traces to Tempo.
*References:* [Overview](00-overview.md), [Task 01](01-deploy-collector-tempo-loki-grafana.md)

## Decisions (2026-10-24)
1. No extra policy for `402` (declined card): by the OpenTelemetry HTTP conventions a 4xx is not an error, so such traces are kept only by the 25 % baseline. The incident demo (task 07) uses a real failure (a service down → 5xx), which the `errors` policy keeps.

## Acceptance Criteria
- [ ] Receiver `otlp` with gRPC (4317) and HTTP (4318).
- [ ] Traces pipeline: `otlp` → `memory_limiter` → `tail_sampling` → `batch` → `otlp` exporter to Tempo. Order matters: `memory_limiter` first, `batch` after sampling.
- [ ] `tail_sampling` policies (overview, decision 5), each with a name and a comment:
  - [ ] `errors`: `status_code` = `ERROR` → keep (mandatory: all traces with errors);
  - [ ] `slow`: `latency` > 1000 ms → keep;
  - [ ] `baseline`: `probabilistic` 25 %, the percentage taken from one ConfigMap value so it can be lowered without editing the pipeline.
- [ ] `decision_wait: 10s`, justified in a comment: it must exceed the longest gap inside one trace (relay interval 500 ms + Kafka + consumer + retries), or the Kafka half arrives after the decision and is sampled separately.
- [ ] Collector runs as **one replica** for traces (tail sampling needs all spans of a trace in one instance); documented as a limitation, with the production answer (`loadbalancing` exporter by trace id in front of several samplers).
- [ ] Collector's own metrics (`otelcol_processor_tail_sampling_*`, accepted/refused spans) scraped by Prometheus; show sampled vs dropped counts in the report.
- [ ] Proof: generate successful and failing requests; Tempo contains every failing trace and only the configured share of the successful ones.

## Technical Notes / Constraints
- Which spans are `ERROR`? A 5xx server span is; a 4xx is not (by the OpenTelemetry HTTP semantic conventions). A declined card answered with 402 is therefore *not* an error trace: decide whether the demo needs a policy for it (e.g. on an attribute), and explain.
- The collector configuration is the centrepiece of the report: keep it readable and commented.
