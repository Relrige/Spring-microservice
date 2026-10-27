---
status: DONE
service: infrastructure
---
# Continue the Trace through Kafka (`traceparent` header)

## Context
Events between services go through Kafka, and the trace must continue across the topic: the consumer's span must be a descendant of the request that caused the event.
*References:* [Overview](00-overview.md), `*/outbox/OutboxRelay.java`, `*/config/KafkaConfig.java`, `*/events/*Listener.java`

## The design problem
Events are not sent from the request thread. The business transaction writes an **outbox row**, and `OutboxRelay` publishes it later from a scheduled job, where the original trace is gone. Spring Kafka's automatic observation would only attach the relay's own, new trace. So:
- the trace context must be **captured when the outbox row is written** (inside the request's span) and stored with the row;
- the relay must **send it as the `traceparent` header** of the record (next to the existing event-type and event-id headers), or start the producer span as a child of the stored context.

Question to settle before coding: store the raw `traceparent` string, or trace id + span id columns? And should the relay's producer span be a child of the request (one tree, but the gap between commit and publish shows as a long idle stretch) or a span *linked* to it (separate trace)? The assignment requires one tree.

## Decisions (2026-10-24)
1. The outbox stores the raw `traceparent` string in a nullable column; the relay's producer span is a **child** of the request's span, so the whole flow is one tree (the gap between commit and publish shows as idle time).

## Acceptance Criteria
- [x] Outbox tables of Order, Inventory, Delivery and Catalog get a nullable `traceparent` column (Flyway migration per service); rows written outside a trace (jobs) leave it `null`.
- [x] Each `OutboxRelay` sets the `traceparent` header from the row; a producer span appears in the original trace.
- [x] Every `KafkaTemplate` used by the relays has observation enabled (`setObservationEnabled(true)`: the templates are created manually in `KafkaConfig`, so `spring.kafka.template.observation-enabled` does not reach them), and it must not overwrite the stored context with the relay's.
- [x] Every listener container has observation enabled (`spring.kafka.listener.observation-enabled: true`, plus `setObservationEnabled` on any container factory created by hand), so the consumer span continues from the header.
- [x] Retry and dead-letter topics keep the `traceparent` header (the DLT recoverers copy headers; verify), so a failed event is still part of its trace.
- [x] Logs written in a listener carry the same `traceId` as the request that produced the event.
- [x] Integration test (Testcontainers Kafka) in at least one service: an event published from a traced request is consumed with the same trace id.
- [x] Check in the cluster: paying an order shows order → Kafka `order.events` → delivery / customer / notification in one trace in Tempo.

## Implementation Notes
- `outbox/OutboxTraceContext` in Order, Inventory, Delivery and Catalog (one copy per service, like the outboxes themselves). `current()` asks the Micrometer `Propagator` for the current span's `traceparent`. `restore(traceparent)` makes that context current through `Tracer.traceContextBuilder()` and `newScope`. Without tracing, both fall back to the `NOOP` tracer and propagator.
- Each outbox append stores `current()` in the new nullable `outbox_events.traceparent` column (`VARCHAR(55)`, migrations Order V5, Inventory V6, Delivery V3, Catalog V6). Rows written by jobs and rows from before the migration stay `NULL`.
- The relay sends inside `restore(...)`. The KafkaTemplate's own observation then creates the producer span as a child of the stored context and writes its `traceparent` header, so the relay never sets the header itself and cannot overwrite it.
- Templates: Order, Inventory and Delivery create theirs in `KafkaConfig` and now call `setObservationEnabled(true)`. Catalog sends with Boot's template, switched on through `spring.kafka.template.observation-enabled`. Catalog's dead-letter recoverer no longer reuses that template: an unobserved copy keeps the failed record's own `traceparent` instead of adding a second one.
- Listeners: `spring.kafka.listener.observation-enabled: true` in all six consuming services (Order, Inventory, Delivery, Catalog, Customer, Notification); none creates its container factory by hand.
- Dead letters and retry topics: the recoverers copy the record's headers, `traceparent` included; the dead-letter templates have no observation, so nothing replaces it.
- Tests:
  - Order `OutboxIntegrationTest`: an event written inside a span stores that span's `traceparent`, and is published with the same trace id under a new (producer) span id; an event written outside a trace stores `NULL`.
  - Inventory `ConsumedEventsIntegrationTest`: a `ProductCreated` with a `traceparent` header is handled in that trace, and a poison message reaches the DLT with its `traceparent`.

## Verification (minikube, 2026-10-24)
- Images of the six services rebuilt and rolled out on `voltstore-np`.
- Place order, one trace (14 spans without the security-filter spans): gateway → order → catalog and inventory over HTTP. Then:
  - inventory `inventory.stock-events send` → catalog `process`;
  - order `order.order-events send` → inventory, notification, customer and delivery `process`.
- Pay, one trace: gateway → order → payment, then `order.order-events send` → inventory, delivery, notification and customer `process`.
- The listeners' log lines carry the same trace id, e.g. Delivery's `Created TTN_PENDING shipment for paid order ...`, Notification's `Recorded PAYMENT_RECEIVED ...` and Customer's cart cleanup.
- Tests: Order 374, Inventory 397, Delivery 165, Catalog 291, Customer 130, all green; Notification 86 with the known locale-only `EventReaderTest` failure (task 02).
- The topic is `order.order-events`, not `order.events` as the acceptance criterion says.

## Technical Notes / Constraints
- Payment and Auth publish no events; check each service's outbox before writing migrations.
- An event fans out to several consumer groups: the trace becomes a tree with several consumer branches under one producer span, which is exactly the picture the report needs.
