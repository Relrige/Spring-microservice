---
status: DONE
service: infrastructure
---
# JSON Logs with `traceId` and Shipping Them to Loki

## Context
All services must write JSON logs whose `traceId` matches the trace in Tempo, and the logs must reach Loki.
*References:* [Overview](00-overview.md) (decisions 2 and 4)

## Acceptance Criteria
- [x] Every service logs ECS JSON to the console via Spring Boot structured logging (`logging.structured.format.console: ecs`), always on (no profile), same format everywhere.
- [x] Each line contains `traceId` and `spanId` (Micrometer Tracing puts them in the MDC), the service name, level, logger and message; field names identical in all services (check one line from each service).
- [x] Lines logged outside a trace (startup, jobs before a span exists) are still valid JSON, without the trace fields.
- [x] No secrets or personal data in logs (tokens, card numbers, passwords); grep the log statements touched by this task.
- [x] Logs pipeline in the collector: `filelog` (pods of namespace `voltstore`, container runtime parser, JSON body parsing) → `k8sattributes` → `batch` → Loki (OTLP HTTP).
- [x] Loki labels kept low-cardinality: service name, namespace, pod at most; **`traceId` is not a label** (it is a field/structured metadata, queried with a filter).
- [x] Check: `{service_name="order-service"} | json | traceId="<id>"` in Grafana returns the lines of one request.

## Implementation Notes
- All nine `application.yml` files end with `logging.structured.format.console: ecs`, with no profile. A line looks like `{"@timestamp":…,"log":{"level","logger"},"process":{…},"service":{"name","version"},"message":…,"traceId":…,"spanId":…,"ecs":{"version":"8.11"}}`. An exception adds `error.*` with the stack trace in the same line.
- Agent (`otel-agent-config.yaml`), after the `container` parser:
  - `json_parser` runs only on lines starting with `{"@timestamp"`, so Kafka and PostgreSQL text passes through. It parses into a temporary `attributes.ecs` and sets the record's timestamp and severity.
  - `trace_parser` runs only when `traceId` exists and sets the record's trace and span id.
  - `remove` drops the temporary attribute.
  - The body stays the original JSON line, exactly what `kubectl logs` shows, so `| json` works in Loki.
- Loki (`loki-config.yaml`): `otlp_config.resource_attributes` with `ignore_defaults: true` indexes only `service.name`, `k8s.namespace.name` and `k8s.pod.name`. Container, deployment, node and pod uid become structured metadata, and so do `trace_id`, `span_id` and the severity.
- Two ways to query one request:
  - `{service_name="order-service"} | json | traceId="<id>"` (the task's check; parses each line);
  - `{k8s_namespace_name="voltstore"} | trace_id="<id>"` (structured metadata, no parsing, all services; task 06 links spans to this one).
- Secrets: the log statements were grepped for token, password, card, email, phone and address. They name headers and log ids only, never values.

## Deviations
- A few lines per pod start are not JSON: the JVM itself prints them to stderr before logging starts (`Picked up JAVA_TOOL_OPTIONS …` and protobuf's `sun.misc.Unsafe` warnings). Logback cannot format them; they reach Loki as plain text without a trace id.

## Verification (minikube, 2026-10-25)
- Field check, one line per service from `kubectl logs`:
  - all nine have `@timestamp`, `log.level`, `log.logger`, `service.name` and `message`;
  - lines inside a request also have `traceId` and `spanId` (Customer, Delivery, Notification, Order and Payment);
  - gateway, Auth, Catalog and Inventory log nothing at INFO while serving a request, and their other lines have no trace fields.
- Loki labels: only `k8s_namespace_name`, `k8s_pod_name` and `service_name` (before: also `k8s_container_name` and `k8s_deployment_name`).
- After a checkout:
  - `{service_name="order-service"} | json | traceId="<checkout trace>"` returned that request's Order lines;
  - `| trace_id="<pay trace>"` returned the lines of Payment, Notification, Delivery and Customer, with severity `INFO`.
- Tests: gateway 388, Auth 98, Catalog 291, Delivery 165, Inventory 397, Order 374 and Payment 119 are all green. Two known failures remain: Customer 130 with `CartRepositoryTest.changes_moveUpdatedAtForward` (the Docker VM clock runs ahead of Windows), and Notification 86 with the locale-only `EventReaderTest` (task 02).

## Technical Notes / Constraints
- Why `traceId` must not be a Loki label: every value creates a new stream; millions of streams make Loki slow and expensive. Same reason the Prometheus metrics avoid order IDs as labels.
- If decision 2 chose the OTLP Logback appender instead of file tailing, replace the `filelog` items with the appender setup and explain the trade-off.
