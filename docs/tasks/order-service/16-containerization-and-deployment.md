---
status: IN_PROGRESS
service: order-service
---
# Align Containerization and Kubernetes Deployment

## Context
Order Service has a Dockerfile, a Compose entry and Kubernetes manifests written for the draft. The reworked service needs a Flyway schema, the shared `INTERNAL_TOKEN`, Kafka and the URLs of four services. This task verifies the whole checkout in Compose (and Kubernetes) with the real neighbours, which is also the first end-to-end run for Inventory's, Delivery's and Customer's Order consumers.
*References:* [Inventory containerization task](../inventory-service/18-containerization-and-deployment.md), [Payment containerization task](../payment-service/09-containerization-and-deployment.md), [Gateway routes task](../api-getaway/10-remaining-service-routes.md)

## Acceptance Criteria
- [x] The image builds and starts in Compose; Flyway applies all migrations on an empty Postgres 18 volume; Hibernate `validate` passes.
- [x] Secrets split as in the other services: `order-db-credentials` (database) and `order-secrets` (`INTERNAL_TOKEN`, app pod only); `k8s/order/app-secret-template.yaml` added; the deployment loads the ConfigMap and both secrets.
- [x] `docker-compose.yml`, `.env.example` and the ConfigMap contain every variable of the spec's *Configuration* table, with comments (`PAYMENT_READ_TIMEOUT` ≥ 6 s).
- [x] Probes work and are not blocked by security.
- [x] Gateway routes for Order added ([gateway task 10](../api-getaway/10-remaining-service-routes.md)): customer routes `Required` + `CUSTOMER`, worker routes `INVENTORY_WORKER`.
- [x] **End-to-end smoke test through the gateway** with a registered customer's JWT: validate → submit (`201`, stock reserved in Inventory) → pay with `4242` (`PAID`; Delivery creates a shipment; Customer clears the cart) → `TTNGenerated` moves the order to `PENDING_SHIPPING` → worker ships (`SHIPPED`; Inventory consumes the reservation) → the mock carrier reports delivery (`DELIVERED`). Plus: pay with `0002` → `402`; cancel a paid order → stock released, shipment cancelled, refund made; an unpaid order expires; without a token every `/orders` route is `401`; another customer's order is `404`.
- [ ] The old Compose volume of the draft (`ddl-auto: update` table) is reset before the run (a destructive step for the team, as in Payment task 09).

## Technical Notes / Constraints
- The run needs Auth ([task 09](../auth-service/09-email-claim-in-jwt.md)), the gateway ([task 11](../api-getaway/11-forward-user-email-header.md)) and Catalog ([task 24](../catalog-service/24-stock-status-in-batch-get.md)) done first.
- Use an isolated Compose project for the run, so existing containers and volumes are not touched.

## Implementation (deployment part)
Done on 2026-10-17; the end-to-end run followed the same day (see *End-to-end run*):
- `k8s/order/app-secret-template.yaml`: `order-secrets` with `INTERNAL_TOKEN` (app pod only; same value as the other services' internal token, at least 32 characters). `app-deployment.yaml` now loads `order-config`, `order-db-credentials` and `order-secrets`; its comment says why more replicas are safe (outbox advisory lock, the cancellation job's per-order claim, the idempotent expiry job, the consumer group). `app-config.yaml` has every variable of the spec's *Configuration* table, with comments (`ORDER_CANCELLATION_RETRY_INTERVAL`, `ORDER_EXPIRY_INTERVAL`, `ORDER_EXPIRY_GRACE` added). The Service stays `ClusterIP` on port `8082`.
- `docker-compose.yml`: `order-service` now also waits for a healthy `kafka` (outbox relay and Delivery-events listener), like Customer. `.env.example` documents every variable; the local `.env` has the required ones (the rest have defaults). The Dockerfile needed no change (the same as Payment's).
- **How the Compose check was run:** an isolated Compose project (`voltstore-t16`: Kafka, Postgres 18 and Order built from this folder, no fixed container names, host port `18082`), so the existing `spring-microservice` containers and volumes were not touched and the database volume started empty. Removed afterwards (`down -v --rmi local`).
- **Results:** the image built and started in about 8 s; Flyway applied V1 and V2 on the empty volume and Hibernate `validate` passed; `/actuator/health/readiness` → `200` without any header; Order declared `order.order-events` (3 partitions) and `order.delivery.shipment-events.dlt` (1 partition); the Delivery-events listener waited for the missing `delivery.shipment-events` without failing startup. Requests: no headers → `401`; customer `GET /orders` → `200` empty page; worker queue → `200 {deliveryDataAvailable: true, orders: []}`; customer on the queue → `403`; a random order ID → `404`; validate without Catalog → `503` with the fixed text; `POST /orders` without `X-User-Email` → `401`. The internal token appears nowhere in the container log.

## End-to-end run (2026-10-17)
Prerequisites done the same day: [Auth task 09](../auth-service/09-email-claim-in-jwt.md), [gateway task 11](../api-getaway/11-forward-user-email-header.md), [Catalog task 24](../catalog-service/24-stock-status-in-batch-get.md) and the Order part of [gateway task 10](../api-getaway/10-remaining-service-routes.md). Runbook: [docker-compose-order.md](../../../e2e/manual/docker-compose-order.md).
- **Setup:** all eight services, their databases and Kafka in a fresh Compose project with empty volumes (in a cloud container, so no team data was touched). Short timings: `ORDER_PAYMENT_WINDOW=1m`, `ORDER_EXPIRY_INTERVAL=10s`, `ORDER_EXPIRY_GRACE=10s`, `DELIVERY_CARRIER_MOCK_DELIVERY_DELAY=10s`. A first try with a 5 s grace was stopped by Order's startup check (grace must exceed `PAYMENT_READ_TIMEOUT`, 8 s), as designed.
- **Happy path:** validate `204` → submit `201 PENDING_PAYMENT` (Inventory reserved 1) → pay with `…4242` `200 PAID` (Customer cleared the cart) → `PENDING_SHIPPING` within 2 s (queue: `TTN_GENERATED` with the TTN) → ship `200 SHIPPED` (Inventory consumed both reservations a moment later) → `DELIVERED` about 14 s later; history shows the order.
- **Declined card** `…0002` → `402` `insufficient_funds`, order stays `PENDING_PAYMENT`. **Cancel a paid order** (`PENDING_SHIPPING`) → `202 CANCELLED`, reservation released, Delivery shipment `CANCELLED`, Payment logged an approved refund. **Unpaid order** → `EXPIRED` after about 80 s, stock released; paying then → `409`.
- **Access:** without a token every `/orders` route is `401`; another customer's order is `404` for `GET`, `DELETE` and pay; a forged `X-User-Id` is replaced by the gateway (`404`); customer on the queue, worker submitting and admin reading history are `403`; a non-UUID id is `404`. The email appeared in neither the gateway nor the Order log; no `ERROR` lines in either.
- **Found on the way:** `payment-service/.env.example` had a comment glued onto the `SPRING_DATASOURCE_PASSWORD` line, so a `.env` copied from it gave Payment a wrong database password; fixed.

## Still open
- **Old data:** the Compose volume `spring-microservice_order-service-postgres` still exists and holds the draft's `ddl-auto: update` table without a Flyway history, so Flyway will refuse to start on it. It has to be reset once (a destructive step for the team to run): `docker compose rm -sf order-service order-service-postgres` and `docker volume rm spring-microservice_order-service-postgres`; in minikube delete and re-create the PVC `order-postgres-pvc` if the draft was deployed there.
- **Minikube** was not run: create `order-secrets` from the template with the shared token, build and load the image (`minikube image load voltstore/order-service:latest`), `kubectl apply -f k8s/order/`.
- While Delivery's topic does not exist, the consumer logs an `UNKNOWN_TOPIC_OR_PARTITION` warning about once a second; it stops as soon as Delivery declares the topic (in the full Compose setup, at Delivery's start).
