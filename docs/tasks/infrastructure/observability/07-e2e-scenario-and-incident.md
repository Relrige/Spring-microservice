---
status: DONE
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
- [x] Scripted, repeatable steps (frontend, `e2e/` or curl) with the seed data; the trace id of the run is recorded. Successful traces are kept at 25 % (decision 5), so repeat the run until its trace is in Tempo, and say so in the report.
- [x] Screenshot of the trace tree in Tempo showing at least gateway, order, inventory, payment, the Kafka producer and at least one consumer service.
- [x] Screenshot of *span → logs* for that trace, showing JSON lines from several services with the same `traceId`.
- [x] Incident simulated, for example (choose one, document why):
  - a dependency is down (scale `inventory-service` to 0 → 5xx at checkout);
  - a slow dependency (payment mock delay → timeouts and retries visible as repeated client spans);
  - a poison event (consumer fails → retry/DLT spans in the same trace).
- [x] Diagnostic loop recorded (video or screenshot series): alert or user-visible error → the error trace (kept by tail sampling) → failing span and its attributes → logs of that span in Loki → cause → fix → a new successful trace.
- [x] Before/after: the failing trace is in Tempo even though successful traces are sampled.

## Decisions (2026-10-25)
1. Incident: **a dependency is down** (`inventory-service` scaled to 0). It needs no code change or test hook, it is what an outage looks like in production, and it exercises every part of the loop: an alert (`ServiceMissing`), a 5xx the user sees, error spans kept by the tail sampler, Resilience4j retries and the circuit breaker visible as repeated client spans, and error logs in Loki.

## Implementation Notes
- Scenario script: [`docs/reports/tracing-logging/e2e-trace.sh`](../../../reports/tracing-logging/e2e-trace.sh). It signs in as the seed customer, places an order for an in-stock product and pays it with the test card `4242…`, through the gateway, the same two requests as the frontend. Each request carries its own `traceparent`, so its trace id is known before Tempo has it. The run repeats until Tempo has the pay trace (25 % baseline).
- Place and pay are two HTTP requests, so they are two traces: reserving stock happens in the place trace. The pay trace covers gateway → order → payment → `order.order-events send` → inventory, notification, customer and delivery (Kafka consumers), which is the tree the criterion asks for. Inventory appears in it as a consumer.
- Screenshots are in `docs/reports/tracing-logging/`, taken in Grafana Explore (kiosk mode) in the browser pane, which is about 800 px wide:

  | File | Shows |
  |---|---|
  | `01-e2e-trace.jpg` | pay trace `43e39f83e2ceeb5c88cee5585d467524`: gateway → order → payment, producer, four consumers |
  | `02-span-to-logs.jpg` | *Logs for this span* clicked on the Order span: the split pane runs `{service_name="order-service"} \| trace_id="43e39f…"` |
  | `03-trace-logs-all-services.jpg` | the JSON lines of that trace from Customer, Delivery, Notification, Order and Payment, all with the same `traceId` |
  | `04-error-traces.jpg` | TraceQL `{ status = error && resource.service.name = "order-service" }`: the incident's traces |
  | `05-incident-trace.jpg` | incident trace `b05f8db089ae8bdfe01b50abfb9d61c2` (3.6 s): five red client spans to inventory |
  | `06-failing-span.jpg` | the first failing span: client `inventory-service`, status error, "I/O error on POST … /inventory/reservations" |
  | `07-failing-span-to-logs.jpg` | *Logs for this span* on the failing span (1 error, 3 warnings) |
  | `08-incident-logs.jpg` | the same Order lines full width: `ERROR … Inventory unavailable: ResourceAccessException … answering 503`, then the circuit breaker opening |
  | `09-after-fix-trace.jpg` | after the fix, pay trace `d0f2e5c39159e005b66d64e8439c4179`, no error spans |

## Verification (minikube, 2026-10-25)
- Scenario: three runs before the tail sampler kept a pay trace (`43e39f83e2ceeb5c88cee5585d467524`, order `91412b3b-3651-4a3a-a226-f7f9d1b48d8b`).
- Incident, 09:01 UTC:
  - `kubectl -n voltstore scale deploy/inventory-service --replicas=0`;
  - 12 checkouts, one every 10 s: all answered `503` with the problem `service-unavailable` ("a required service is unavailable").
- The loop:
  1. **Alert and user-visible error:** `ServiceMissing inventory-service` firing; `HighHttpErrorRate` and `HighHttpLatency` pending for `api-gateway` and `order-service` (Prometheus `/api/v1/alerts`); the 503 at checkout.
  2. **Error trace:** all 12 error traces kept by the tail sampler.
  3. **Failing span:** each trace shows Order's `POST /inventory/reservations` failing with `ResourceAccessException`. There are three attempts (Resilience4j retries, then the circuit breaker opens), followed by failed `…/release` calls; the 2 s release span is the client timeout.
  4. **Logs of that span:** `ERROR GlobalExceptionHandler: Inventory unavailable: ResourceAccessException … answering 503`, plus `WARN` lines for the open circuit breaker and the release that `OrphanReservationJob` retries later.
  5. **Cause:** inventory has no pod (`kubectl get deploy inventory-service` → 0/0).
  6. **Fix:** scaled back to 1 replica.
  7. **Confirmation:** the next run succeeded and was kept (`d0f2e5c39159e005b66d64e8439c4179`).
- Before and after: 12 of 12 error traces are in Tempo, while successful runs needed retries to get one kept (task 04: 7 of 40).
- The 503 server spans themselves are not marked `error`: Micrometer marks a server span as error only on an exception, and the 503 is an answer. The failing **client** spans are errors, so the traces are kept.
- In the trace list, "root span not yet received" appears because the script sends its own `traceparent`: the true root is the script's span, which is never exported.
