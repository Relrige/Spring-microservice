---
status: TODO
service: infrastructure
---
# Tracing in the Services and W3C Trace Context over HTTP

## Context
Every service (7 Spring Boot services and the API Gateway) must create spans, send them to the collector over OTLP, and pass the trace context to the next service in the W3C `traceparent` header, so one browser request becomes one trace.
*References:* [Overview](00-overview.md), `*/clients/ClientsConfiguration.java`, `*/filters/CorrelationIdFilter.java`

## Acceptance Criteria
- [ ] Each service has Micrometer Tracing with the OpenTelemetry bridge and the OTLP span exporter (in Spring Boot 4: `spring-boot-starter-opentelemetry`; verify the starter and property names for the version in use).
- [ ] Propagation type is W3C (`management.tracing.propagation.type: w3c`, the default; set explicitly so the choice is visible).
- [ ] **Sampling in the services is 100 %** (`management.tracing.sampling.probability: 1.0`); the default is 10 %, and head sampling would throw traces away before the tail sampler (task 04) could see their errors.
- [ ] OTLP endpoint comes from the environment (ConfigMap per service), e.g. `http://otel-collector:4318/v1/traces`; Compose and tests do not fail when no collector is running.
- [ ] Every span carries `service.name` = `spring.application.name` (a resource attribute).
- [ ] **HTTP clients are instrumented.** All `ClientsConfiguration` classes build their client with the static `RestClient.builder()`, which has no `ObservationRegistry`, so no client span and no `traceparent` header. Use the auto-configured `RestClient.Builder` (inject it), or set `.observationRegistry(...)` on the builder; keep the timeouts, the internal-token interceptor and the error handling unchanged.
- [ ] The API Gateway (`spring-cloud-starter-gateway-server-webmvc`) creates the root server span and forwards `traceparent` to the routed service (check that its proxy client is instrumented; fix if not).
- [ ] `/actuator/**` requests (probes, Prometheus scrapes) produce no traces (an `ObservationPredicate` or the equivalent property), otherwise Tempo fills with scrape spans.
- [ ] `@Scheduled` runs of the outbox relays and the recovery jobs produce no traces of their own (4 relays at 500 ms would add ~8 empty traces per second); the relay's send continues the stored request trace instead (task 03).
- [ ] The correlation id is removed (overview, decision 3): `CorrelationIdFilter`, `CorrelationIdInterceptor`, `CorrelationIdKeys` and the `MDC.put` UUIDs in Delivery's `TtnGenerationJob` and `DeliveryReportService`.
- [ ] Test per service: a request through a WireMock-backed client carries a `traceparent` header whose trace id equals the current span's trace id.
- [ ] Check in the cluster: one checkout request shows gateway → order → inventory/payment spans in Tempo with the same trace id.

## Technical Notes / Constraints
- `traceparent` format: `00-<32 hex trace id>-<16 hex parent span id>-<flags>`; flag `01` = sampled. Explain each part in the report.
- Async work started from a request (thread pools, `@Async`) loses the context unless the executor is context-propagating; the scheduled jobs start their own traces, which is correct.
- Resilience4j retries make several client spans under one parent; this is useful in the incident demo (task 07).
