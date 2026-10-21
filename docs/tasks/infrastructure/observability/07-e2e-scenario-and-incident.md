---
status: TODO
service: infrastructure
---
# End-to-End Scenario and Incident Investigation

## Context
Run a real business scenario that produces one trace across synchronous calls and a Kafka event, then simulate an incident and walk the diagnostic loop: symptom → trace → logs → cause → fix → confirmation.
*References:* [Overview](00-overview.md), `e2e/`, the frontend's `TEST_DATA.md` (seed data and payment cards)

## Scenario (one trace, ≥ 3 services + Kafka)
Customer pays an order (checkout → place → pay), through the API Gateway:
`api-gateway → order → inventory (reserve) → payment (charge) → order outbox → Kafka order.events → delivery, customer (cart cleanup), notification`.

## Acceptance Criteria
- [ ] Scripted, repeatable steps (frontend, `e2e/` or curl) with the seed data; the trace id of the run is recorded. Successful traces are kept at 25 % (decision 5), so repeat the run until its trace is in Tempo, and say so in the report.
- [ ] Screenshot of the trace tree in Tempo showing at least gateway, order, inventory, payment, the Kafka producer and at least one consumer service.
- [ ] Screenshot of *span → logs* for that trace, showing JSON lines from several services with the same `traceId`.
- [ ] Incident simulated, for example (choose one, document why):
  - a dependency is down (scale `inventory-service` to 0 → 5xx at checkout);
  - a slow dependency (payment mock delay → timeouts and retries visible as repeated client spans);
  - a poison event (consumer fails → retry/DLT spans in the same trace).
- [ ] Diagnostic loop recorded (video or screenshot series): alert or user-visible error → the error trace (kept by tail sampling) → failing span and its attributes → logs of that span in Loki → cause → fix → a new successful trace.
- [ ] Before/after: the failing trace is in Tempo even though successful traces are sampled.
