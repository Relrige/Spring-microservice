---
status: DONE
service: infrastructure
---
# Collector Pipelines: OTLP Receiver, Tail Sampling, Export to Tempo

## Context
The collector receives all spans over OTLP, decides per complete trace which ones to keep (every trace with an error must be kept), and exports the kept traces to Tempo.
*References:* [Overview](00-overview.md), [Task 01](01-deploy-collector-tempo-loki-grafana.md)

## Decisions (2026-10-24)
1. No extra policy for `402` (declined card): by the OpenTelemetry HTTP conventions a 4xx is not an error, so such traces are kept only by the 25 % baseline. The incident demo (task 07) uses a real failure (a service down → 5xx), which the `errors` policy keeps.

## Acceptance Criteria
- [x] Receiver `otlp` with gRPC (4317) and HTTP (4318).
- [x] Traces pipeline: `otlp` → `memory_limiter` → `tail_sampling` → `batch` → `otlp` exporter to Tempo. Order matters: `memory_limiter` first, `batch` after sampling.
- [x] `tail_sampling` policies (overview, decision 5), each with a name and a comment:
  - [x] `errors`: `status_code` = `ERROR` → keep (mandatory: all traces with errors);
  - [x] `slow`: `latency` > 1000 ms → keep;
  - [x] `baseline`: `probabilistic` 25 %, the percentage taken from one ConfigMap value so it can be lowered without editing the pipeline.
- [x] `decision_wait: 10s`, justified in a comment: it must exceed the longest gap inside one trace (relay interval 500 ms + Kafka + consumer + retries), or the Kafka half arrives after the decision and is sampled separately.
- [x] Collector runs as **one replica** for traces (tail sampling needs all spans of a trace in one instance); documented as a limitation, with the production answer (`loadbalancing` exporter by trace id in front of several samplers).
- [x] Collector's own metrics (`otelcol_processor_tail_sampling_*`, accepted/refused spans) scraped by Prometheus; show sampled vs dropped counts in the report.
- [x] Proof: generate successful and failing requests; Tempo contains every failing trace and only the configured share of the successful ones.

## Implementation Notes
- `k8s/observability/otel-collector-config.yaml`: the traces pipeline is `otlp` → `memory_limiter` → `tail_sampling` → `batch` → `otlp/tempo`. The three policies are `errors`, `slow` and `baseline`, each commented; `decision_wait: 10s`, `num_traces: 50000`. A trace is kept if any policy keeps it.
- The baseline percentage is the only value in a second ConfigMap, `otel-collector-sampling` (same file). It reaches the collector as the env var `BASELINE_SAMPLING_PERCENTAGE` (`configMapKeyRef` in `otel-collector-deployment.yaml`), and the config reads it as `${env:BASELINE_SAMPLING_PERCENTAGE}`. A change takes effect after `kubectl -n voltstore rollout restart deploy/otel-collector`.
- One replica with the `Recreate` strategy was already set up in task 01; the deployment comment names the production answer (a `loadbalancing` exporter routing by trace id in front of several samplers).
- The config was checked with `otelcol-contrib validate` (image 0.130.0) before deploying.
- Prometheus names: the collector exports UTF-8 metric names with the unit in braces, so queries need the quoted form, e.g. `sum by (policy, sampled) ({"otelcol_processor_tail_sampling_count_traces_sampled_{traces}_total"})`.

## Verification (minikube, 2026-10-25)
- `sampling-proof` run through the gateway, each request with its own known trace id:
  - 40 successful `GET /catalog/products`: Tempo kept 7 (17.5 %; at 25 % about 10 ± 3 are expected);
  - 10 requests with `catalog-service` scaled to 0 (gateway answers 502, error spans): Tempo kept 10 of 10.
- Collector counters since its start (all traffic, startup traces included): 119 traces kept, 407 dropped. Per policy: `baseline` 111 kept, `errors` 10, `slow` 5; a trace can match more than one policy.
- Prometheus scrapes these counters from the collector (target `up`), so the report can show sampled vs dropped counts per policy.

## Technical Notes / Constraints
- Which spans are `ERROR`? A 5xx server span is; a 4xx is not (by the OpenTelemetry HTTP semantic conventions). A declined card answered with 402 is therefore *not* an error trace: decide whether the demo needs a policy for it (e.g. on an attribute), and explain.
- The collector configuration is the centrepiece of the report: keep it readable and commented.
