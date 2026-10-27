---
status: DONE
service: infrastructure
---
# Distributed Tracing and Centralized Logs: Overview

## Context
Group assignment: deploy the OpenTelemetry Collector, Grafana Tempo and Grafana Loki in the Kubernetes cluster; propagate W3C Trace Context through synchronous HTTP calls and through Kafka messages (`traceparent` header); receive spans over OTLP in the collector, tail-sample them keeping every trace with an error, and export them to Tempo. Then run an end-to-end business scenario that produces one trace covering HTTP calls and a Kafka event, and write a shared report with the collector configuration, the interaction architecture and a demonstration of the diagnostic loop.

Acceptance as stated by the assignment:
- one trace links calls of **at least three microservices and a Kafka topic** into one tree;
- the logs of **all services are JSON** with a consistent `traceId`;
- in Grafana, **one click from a span opens its logs in Loki**;
- an archive with configurations, manifests, the shared report and a video or screenshots of the incident investigation.

*References:* [Monitoring task (same style, same namespace)](../monitoring-prometheus-alertmanager.md), [k8s/monitoring/](../../../../k8s/monitoring/), [Network policies](../../../../k8s/network/network-policies.yaml)

## Target architecture
```
 browser ──► api-gateway ──HTTP(traceparent)──► order ──HTTP──► inventory, payment, catalog, delivery
                                                 │
                                                 └─ outbox row (+ stored traceparent) ─► relay ─► Kafka (traceparent header)
                                                                                                    │
                                                    delivery, customer, notification, inventory ◄──┘ listeners continue the trace

 every service ──OTLP (spans)──► otel-collector (gateway: tail sampling) ──► Tempo ◄──┐
 every pod's stdout (JSON) ──► otel-collector agent (filelog) ──────────────► Loki  ◄──┴── Grafana (span → logs)
```

## Tasks (in order)
| # | Task | Depends on |
|---|---|---|
| 01 | [Deploy Collector, Tempo, Loki and Grafana](01-deploy-collector-tempo-loki-grafana.md) | |
| 02 | [Tracing in the services and W3C propagation over HTTP](02-http-tracing-w3c.md) | 01 |
| 03 | [Continue the trace through Kafka (`traceparent`)](03-kafka-trace-propagation.md) | 02 |
| 04 | [Collector pipelines: OTLP, tail sampling, export to Tempo](04-collector-tail-sampling.md) | 01, 02 |
| 05 | [JSON logs with `traceId` and shipping them to Loki](05-json-logs-to-loki.md) | 01, 02 |
| 06 | [Grafana: span → logs in one click](06-grafana-trace-to-logs.md) | 04, 05 |
| 07 | [End-to-end scenario and incident investigation](07-e2e-scenario-and-incident.md) | 03, 06 |
| 08 | [Shared report and submission archive](08-report-and-archive.md) | 07 |

02–03 (service code) and 01/04/05 (cluster) can go in parallel once 01 is deployed.

## Decisions to make first (record each in the task where it lands)
1. **One collector or two? Decided: two.** An *agent* DaemonSet reads the pod log files on its node and ships them to Loki; a *gateway* Deployment (1 replica) receives all spans from the services, tail-samples them and exports to Tempo. Why: log files are local to each node (needs one collector per node), while tail sampling must see every span of a trace in one process (needs exactly one instance). A single DaemonSet doing both would work on one-node minikube but silently break sampling once a second node is added.
2. **How do logs reach Loki? Decided: stdout + file tailing.** Services only write JSON lines to stdout; the agent collector tails `/var/log/pods` (`filelog` receiver) and ships to Loki. Why: no logging library or network dependency in the services, lines wait in the file while the collector or Loki is down, startup crashes and non-Java pods (Kafka, Postgres) are collected too, and `kubectl logs` shows exactly what Loki has. Rejected: the OpenTelemetry Logback appender (OTLP from the app), which drops lines when its buffer fills and adds a dependency to all services. Limitation: if the agent is down longer than the kubelet keeps rotated files, those lines are lost.
3. **The existing correlation id: decided, remove it.** `CorrelationIdFilter`, `CorrelationIdInterceptor`, `CorrelationIdKeys` and the UUIDs in Delivery's jobs are replaced by the trace id. Why: it was a placeholder (the gateway never set it, Payment/Auth/Notification lacked it, it did not cross Kafka, and no log pattern printed it), while tracing covers all services, Kafka and `@Scheduled` runs, and puts `traceId` in every log line. Two ids per request would contradict "consistent `traceId`".
4. **JSON log format: decided, ECS, always on.** `logging.structured.format.console: ecs` in every service's `application.yml`, not behind a profile, so tests, IDE runs, Compose and Kubernetes print the same lines. Why ECS: a documented schema with `service.name`/version/environment, a clear `log.level` for severity mapping and `error.*` fields for exceptions (one line per entry, stack trace included); Logstash lacks the service name, GELF uses syslog numbers and `_`-prefixed fields. Always on: what is tested is exactly what Loki receives; the cost is a less readable local console.
5. **Tail-sampling policies: decided.** (1) `errors`: `status_code ERROR`; (2) `slow`: `latency > 1000 ms` (same threshold as the `HighHttpLatency` alert); (3) `baseline`: `probabilistic 25 %` of the remaining traces (high enough that the happy-path demo trace is kept after a few runs, low enough that the sampler visibly drops traffic). `decision_wait: 10s` (covers relay interval 500 ms + Kafka + consumer + retries). 4xx business answers (402 declined, 409/422 stock) stay `UNSET`, not errors, and get no extra policy. Empty `@Scheduled` runs (outbox relays, recovery jobs) create no traces (excluded in the services, task 02). The percentage is one ConfigMap value; production would lower it further (e.g. 1–10 %), while policies 1 and 2 keep every interesting trace regardless.
