---
status: DONE
service: infrastructure
---
# Tracing in the Services and W3C Trace Context over HTTP

## Context
Every service (7 Spring Boot services and the API Gateway) must create spans, send them to the collector over OTLP, and pass the trace context to the next service in the W3C `traceparent` header, so one browser request becomes one trace.
*References:* [Overview](00-overview.md), `*/clients/ClientsConfiguration.java`, `*/filters/CorrelationIdFilter.java`

## Acceptance Criteria
- [x] Each service has Micrometer Tracing with the OpenTelemetry bridge and the OTLP span exporter (in Spring Boot 4: `spring-boot-starter-opentelemetry`; verify the starter and property names for the version in use).
- [x] Propagation type is W3C (`management.tracing.propagation.type: w3c`, the default; set explicitly so the choice is visible).
- [x] **Sampling in the services is 100 %** (`management.tracing.sampling.probability: 1.0`); the default is 10 %, and head sampling would throw traces away before the tail sampler (task 04) could see their errors.
- [x] OTLP endpoint comes from the environment (ConfigMap per service), e.g. `http://otel-collector:4318/v1/traces`; Compose and tests do not fail when no collector is running.
- [x] Every span carries `service.name` = `spring.application.name` (a resource attribute).
- [x] **HTTP clients are instrumented.** All `ClientsConfiguration` classes build their client with the static `RestClient.builder()`, which has no `ObservationRegistry`, so no client span and no `traceparent` header. Use the auto-configured `RestClient.Builder` (inject it), or set `.observationRegistry(...)` on the builder; keep the timeouts, the internal-token interceptor and the error handling unchanged.
- [x] The API Gateway (`spring-cloud-starter-gateway-server-webmvc`) creates the root server span and forwards `traceparent` to the routed service (check that its proxy client is instrumented; fix if not).
- [x] `/actuator/**` requests (probes, Prometheus scrapes) produce no traces (an `ObservationPredicate` or the equivalent property), otherwise Tempo fills with scrape spans.
- [x] `@Scheduled` runs of the outbox relays and the recovery jobs produce no traces of their own (4 relays at 500 ms would add ~8 empty traces per second); the relay's send continues the stored request trace instead (task 03).
- [x] The correlation id is removed (overview, decision 3): `CorrelationIdFilter`, `CorrelationIdInterceptor`, `CorrelationIdKeys` and the `MDC.put` UUIDs in Delivery's `TtnGenerationJob` and `DeliveryReportService`.
- [x] Test per service: a request through a WireMock-backed client carries a `traceparent` header whose trace id equals the current span's trace id.
- [x] Check in the cluster: one checkout request shows gateway → order → inventory/payment spans in Tempo with the same trace id.

## Decisions (2026-10-24)
1. The work continues on the branch `grafana` in autopilot mode; the observability tasks change every service at once, so they share one branch and one pull request instead of one branch per service (decided with the user).
2. Library: Spring Boot 4.1's `spring-boot-starter-opentelemetry` (Micrometer Tracing, OpenTelemetry bridge, OTLP exporter). Every service sends spans to the collector at `otel-collector:4318`; Compose and the tests run without a collector.

## Implementation Notes
- All nine services (including the gateway and Notification) got `spring-boot-starter-opentelemetry` and the same `management.tracing` block; `service.name` comes from `spring.application.name` by default (seen in Tempo).
- The exporter exists only when `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` is set (`k8s/<service>/app-config.yaml`), so Compose, tests and IDE runs export nothing and log no export errors.
- `config/TracingConfig` in every service: an `ObservationPredicate` drops server observations for `/actuator/**`. This also drops their `http.server.requests` metrics, which no dashboard or alert uses.
- `management.observations.enable.tasks.scheduled: false` stops traces from every `@Scheduled` run (relays, recovery jobs, Notification sender and cleanup).
- Clients: Order, Catalog, Delivery and Inventory set `.observationRegistry(...)` on their `RestClient.builder()`; timeouts, the internal-token interceptor and error handling are unchanged. The registry comes from an `ObjectProvider` with `NOOP` as fallback, so slim test contexts still work.
- The gateway needed no code change: its proxy client is already instrumented. It continues an incoming `traceparent` and starts a new trace when there is none (`pipeline/TracePropagationTest`).
- Correlation id removed: the filters, interceptors and key classes in Order, Catalog, Customer, Delivery and Inventory. Delivery's `TtnGenerationJob` and `DeliveryReportService` now wrap each attempt in an observation (`delivery.ttn.attempt`, `delivery.report`) instead of putting a UUID into the MDC.
- `TracePropagationTest` in each calling service: a call made inside an observation reaches WireMock with `traceparent: 00-<current trace id>-<16 hex>-0[13]`. The OpenTelemetry SDK sends flags `03` (sampled + random trace id, W3C Trace Context level 2), not `01`.

## Verification (minikube, 2026-10-24)
- All nine images built from this branch and rolled out on `voltstore-np`. The node container hit its 6 GiB limit during the restart (old and new pods side by side) and the API server stopped answering; raising the limit with `docker update --memory 10g` fixed it.
- One checkout through the gateway (place, then pay, as the frontend does), each request sent with its own `traceparent`:
  - place order: one trace `api-gateway` (server, client) → `order-service` → `catalog-service` (`/catalog/products/batch-get`) and `inventory-service` (`/inventory/reservations`), 19 spans;
  - pay: one trace `api-gateway` → `order-service` → `payment-service` (`/payments/charge`), 13 spans;
  - both under the trace id the client sent, so the gateway continued it.
- Tempo search for `/actuator` spans: 0 traces; `order-service` server traces: only the two requests above (no relay or job traces).
- Tests: gateway 388, Auth 98, Catalog 291, Customer 103, Delivery 165, Inventory 395, Order 372, Payment 95, all green. Notification 86 with 1 failure, `EventReaderTest`, which fails without these changes too: it expects English validation messages and the machine locale is Ukrainian (passes with `-Duser.language=en`).

## Technical Notes / Constraints
- `traceparent` format: `00-<32 hex trace id>-<16 hex parent span id>-<flags>`; flag `01` = sampled. Explain each part in the report.
- Async work started from a request (thread pools, `@Async`) loses the context unless the executor is context-propagating; the scheduled jobs start their own traces, which is correct.
- Resilience4j retries make several client spans under one parent; this is useful in the incident demo (task 07).
