---
status: TODO
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
- [ ] Outbox tables of Order, Inventory, Delivery and Catalog get a nullable `traceparent` column (Flyway migration per service); rows written outside a trace (jobs) leave it `null`.
- [ ] Each `OutboxRelay` sets the `traceparent` header from the row; a producer span appears in the original trace.
- [ ] Every `KafkaTemplate` used by the relays has observation enabled (`setObservationEnabled(true)`: the templates are created manually in `KafkaConfig`, so `spring.kafka.template.observation-enabled` does not reach them), and it must not overwrite the stored context with the relay's.
- [ ] Every listener container has observation enabled (`spring.kafka.listener.observation-enabled: true`, plus `setObservationEnabled` on any container factory created by hand), so the consumer span continues from the header.
- [ ] Retry and dead-letter topics keep the `traceparent` header (the DLT recoverers copy headers; verify), so a failed event is still part of its trace.
- [ ] Logs written in a listener carry the same `traceId` as the request that produced the event.
- [ ] Integration test (Testcontainers Kafka) in at least one service: an event published from a traced request is consumed with the same trace id.
- [ ] Check in the cluster: paying an order shows order → Kafka `order.events` → delivery / customer / notification in one trace in Tempo.

## Technical Notes / Constraints
- Payment and Auth publish no events; check each service's outbox before writing migrations.
- An event fans out to several consumer groups: the trace becomes a tree with several consumer branches under one producer span, which is exactly the picture the report needs.
