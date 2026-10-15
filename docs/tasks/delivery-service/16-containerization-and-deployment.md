---
status: DONE
service: delivery-service
---
# Align Containerization and Kubernetes Deployment

## Context
Delivery has a Dockerfile, a Compose entry and Kubernetes manifests from the scaffold, written for the draft (database only). The reworked service also needs Kafka, the internal token and the Inventory URL. This task checks that the complete service runs in Compose and Kubernetes the way Catalog and Inventory do, and unblocks the gateway routes.
*References:* [Catalog containerization task](../catalog-service/20-containerization-and-deployment.md), [Inventory containerization task](../inventory-service/18-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [x] The image builds and starts in Compose after Postgres and Kafka are healthy; Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [x] Secrets split as in the other services: `delivery-db-credentials` (database only) and `delivery-secrets` (internal token, app pod only), with templates in `k8s/delivery/`.
- [x] ConfigMap and `.env.example` contain every variable from the spec's *Configuration* table, with comments; `.env` stays untracked.
- [x] Liveness and readiness probes work and are not blocked by the security chain.
- [x] Manual smoke test recorded in the task notes: publish an `OrderPaid` with `kafka-console-producer` (payload from the event catalogue, header `eventType: OrderPaid`) for a product with packaging in Inventory → the shipment becomes `TTN_GENERATED` and `TTNGenerated` appears on `delivery.shipment-events`; `GET /delivery/shipments` with the internal token shows it; an `OrderShipped` for the same order makes it `IN_TRANSIT`, and after `DELIVERY_CARRIER_MOCK_DELIVERY_DELAY` it is `DELIVERED` and `OrderDelivered` is published; restarting the pod while a shipment is `IN_TRANSIT` still ends in `DELIVERED`; `DELETE` on another shipment gives `CANCELLED`.
- [x] Repeat with `DELIVERY_CARRIER_MOCK_FAILURE_RATE=1` → `TTN_FAILED` after the back-offs, then a manual retry with the rate back at `0` succeeds.
- [x] `k8s.txt` build/load commands include `delivery-service` (verify, do not duplicate).
- [x] Mark [API gateway task 10](../api-getaway/10-remaining-service-routes.md)'s Delivery part as unblocked.

## Technical Notes / Constraints
- One replica is enough; scaling to 2 is a useful check for the job's `SKIP LOCKED` claim and the outbox relay lock if time allows.
- A `NetworkPolicy` is a cross-service concern; mention it, do not create one only for Delivery.
- Update the spec's *Implementation Status* at sprint end.

## Implementation Notes (2026-10-15)
- **Dockerfile:** unchanged; it is already identical to Inventory's (multi-stage Maven build, layered extract, distroless Java 25, non-root user 10001, heap settings from `JAVA_TOOL_OPTIONS`).
- **Compose:** `delivery-service` now also waits for `kafka` (`service_healthy`). Configuration comes only from `delivery-service/.env` (untracked; copy `.env.example`).
- **Kubernetes:** `delivery-db-credentials` (`secret-template.yaml`, database only, also loaded by the Postgres pod) and `delivery-secrets` (`app-secret-template.yaml`, `AUTH_INTERNAL_TOKEN`, app pod only; added in task 04). `app-config.yaml` now lists every variable of the spec's Configuration table except the secret: `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `ORDER_EVENTS_TOPIC`, `INVENTORY_SERVICE_URL` (`:8084`), `INVENTORY_CONNECT_TIMEOUT` / `_READ_TIMEOUT`, `DELIVERY_CARRIER_MOCK_*`. `.env.example` has the same variables (the optional ones commented, with defaults).
- **Probes:** `/actuator/health/**` is `permitAll` (task 04), and readiness answered `UP` in Compose. `MissingSourceTopicsStartupTest` checks readiness without Order's topic.
- **NetworkPolicy:** no change needed. `k8s/network/network-policies.yaml` already admits service-to-service traffic (Delivery → Inventory, Order → Delivery) through the `app.kubernetes.io/component: service` label and has `delivery-db-from-delivery-service`. It is a cross-service file; nothing Delivery-specific was added.
- **`k8s.txt`:** already builds and loads `voltstore/delivery-service:latest`; no duplicate added.
- **Gateway:** [API gateway task 10](../api-getaway/10-remaining-service-routes.md) now states that its Delivery part is unblocked.

### Smoke test (Docker Compose, 2026-10-15)
Stack: `kafka`, `inventory-service(-postgres)`, `delivery-service(-postgres)` from this branch, with an override file outside the repo that only renamed the containers (stopped containers of another checkout held the fixed names) and set `DELIVERY_CARRIER_MOCK_DELIVERY_DELAY=5s`. Inventory data: `ProductCreated` published with `kafka-console-producer` (Catalog was not running), then `PUT /inventory/stock/{id}/packaging` 22×12×6 cm, 180 g. Order's topic created with `kafka-topics.sh` (Compose does not auto-create topics).
1. Empty Postgres 18 volume: Flyway applied V1 and V2, Hibernate `validate` passed, readiness `UP`; the startup recovery logged "handed 0 IN_TRANSIT shipments over".
2. `OrderPaid` (catalogue payload, header `eventType: OrderPaid`, 2 units) → `TTN_GENERATED` within seconds, parcel 22×12×12 cm, 360 g. `TTNGenerated` on `delivery.shipment-events` with key `orderId`, headers `eventType`/`eventId`, payload `{ttn, eventId, orderId, timestamp}`. `GET /delivery/shipments?orderIds=…` with the internal token showed it.
3. `OrderShipped` for the same order → `IN_TRANSIT`, and 5 s later `DELIVERED` with `OrderDelivered` published.
4. A second order shipped, then `docker restart` within the 5 s delay: after the restart the log showed "handed 1 IN_TRANSIT shipments over to the carrier again", and the order ended `DELIVERED` with its `OrderDelivered`.
5. `DELETE /delivery/shipments/{id}` on a third, `TTN_GENERATED` order → `204`, status `CANCELLED` (TTN cancelled at the mock carrier).
6. Recreated with `DELIVERY_CARRIER_MOCK_FAILURE_RATE=1.0`: a new `OrderPaid` → attempts 1–3 failed with `next_attempt_at` +5 s, +30 s, +2 min, then "failed after 4 attempts … TTN_FAILED" with `carrier_error: simulated carrier failure`. Recreated with rate `0.0`: `POST /delivery/orders/{id}/ttn/retry` as `INVENTORY_WORKER` → `200 {"ttn": …}`, status `TTN_GENERATED`; a second retry → `409`.
- Stack and volumes removed afterwards.

**Not verified:** the Kubernetes manifests were not applied to minikube in this session (only reviewed and aligned with Inventory's), and scaling to 2 replicas was not tried; the `SKIP LOCKED` claim and the relay lock are covered by integration tests (two concurrent job runs; Inventory's relay test pattern).
- **Verified (2026-10-15):** the whole module test suite (18 test classes, 157 tests) passes.
