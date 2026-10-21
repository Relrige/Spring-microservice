---
status: TODO
service: infrastructure
---
# JSON Logs with `traceId` and Shipping Them to Loki

## Context
All services must write JSON logs whose `traceId` matches the trace in Tempo, and the logs must reach Loki.
*References:* [Overview](00-overview.md) (decisions 2 and 4)

## Acceptance Criteria
- [ ] Every service logs ECS JSON to the console via Spring Boot structured logging (`logging.structured.format.console: ecs`), always on (no profile), same format everywhere.
- [ ] Each line contains `traceId` and `spanId` (Micrometer Tracing puts them in the MDC), the service name, level, logger and message; field names identical in all services (check one line from each service).
- [ ] Lines logged outside a trace (startup, jobs before a span exists) are still valid JSON, without the trace fields.
- [ ] No secrets or personal data in logs (tokens, card numbers, passwords); grep the log statements touched by this task.
- [ ] Logs pipeline in the collector: `filelog` (pods of namespace `voltstore`, container runtime parser, JSON body parsing) → `k8sattributes` → `batch` → Loki (OTLP HTTP).
- [ ] Loki labels kept low-cardinality: service name, namespace, pod at most; **`traceId` is not a label** (it is a field/structured metadata, queried with a filter).
- [ ] Check: `{service_name="order-service"} | json | traceId="<id>"` in Grafana returns the lines of one request.

## Technical Notes / Constraints
- Why `traceId` must not be a Loki label: every value creates a new stream; millions of streams make Loki slow and expensive. Same reason the Prometheus metrics avoid order IDs as labels.
- If decision 2 chose the OTLP Logback appender instead of file tailing, replace the `filelog` items with the appender setup and explain the trade-off.
