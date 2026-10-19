---
status: DONE
service: notification-service
---
# Containerization, Compose and Kubernetes Deployment (with Mailpit)

## Context
Unlike the other services, Notification has no Dockerfile, Compose entry or Kubernetes manifests from the scaffold. It also needs a new infrastructure piece, **Mailpit**, as the SMTP server (D4). This task makes the complete service run in Compose and Kubernetes the way Customer and Delivery do.
*References:* [Delivery containerization task](../delivery-service/16-containerization-and-deployment.md), [Customer containerization task](../customer-service/09-containerization-and-deployment.md), [Notification Spec — Configuration](../../design/services-specs/notification-service-spec.md)

## Acceptance Criteria
- [x] `Dockerfile` copied from Customer's (multi-stage Maven build, layered extract, distroless Java 25, non-root user 10001, heap from `JAVA_TOOL_OPTIONS`).
- [x] Compose:
  - `notification-service-postgres` (Postgres 18, own volume);
  - `mailpit` (pinned tag; SMTP 1025 internal, UI 8025 published on the host for demos);
  - `notification-service` waiting for its Postgres, `kafka` and `mailpit`.
  Configuration only from `notification-service/.env` (untracked; `.env.example` complete).
- [x] Kubernetes in `k8s/notification/`, mirroring `k8s/customer/`:
  - database Deployment and Service, `notification-db-credentials` template;
  - app ConfigMap with every non-secret variable from the spec's *Configuration* table;
  - app Deployment with liveness and readiness probes and the three `prometheus.io/*` annotations, and its Service.
  No app secret is needed while SMTP has no credentials; say so in the notes.
- [x] Mailpit in Kubernetes: Deployment and Service `mailpit` (ports 1025 and 8025, ClusterIP; UI through `kubectl port-forward`). Decide whether it lives in `k8s/notification/` or a shared folder, and record the choice.
- [x] `k8s/network/network-policies.yaml`: Notification → its database, Notification → Mailpit and Kafka, Prometheus → Notification, following the existing policies. Nothing else may reach Notification's database or Mailpit's SMTP port.
- [x] `k8s.txt` builds and loads `voltstore/notification-service:latest`.
- [x] Manual smoke test recorded in the notes:
  1. on an empty Postgres 18 volume, Flyway applies the migrations and readiness is `UP`;
  2. publish an `OrderPlaced` with `kafka-console-producer` (catalogue payload, header `eventType: OrderPlaced`) → the email appears in the Mailpit UI;
  3. the same record again → no second email;
  4. an `OrderDelivered` on `delivery.shipment-events` for that order → the "delivered" email arrives at the same address;
  5. with Mailpit stopped, a new event leaves the row `PENDING` with growing `attempts`; after Mailpit restarts, the email arrives.

## Technical Notes / Constraints
- Compose does not create topics automatically. Create Order's and Delivery's topics with `kafka-topics.sh` when their services are not running (see the Delivery smoke test).
- One replica is enough. Scaling to 2 is a good check of the job's `SKIP LOCKED` claim if time allows.
- Update the spec's *Implementation Status* at sprint end.

## Implementation Notes (2026-10-18)
- **`notification-service/Dockerfile`:** copied unchanged from Customer (multi-stage Maven build, layered extract, `distroless/java25-debian13:nonroot`, user 10001, heap from `JAVA_TOOL_OPTIONS`).
- **Compose** (`docker-compose.yml`, "notification-service section"):
  - `notification-service-postgres`: Postgres 18, own volume, `pg_isready` health check;
  - `mailpit`: `axllent/mailpit:v1.27.10`, the same tag as the tests. Only the UI/API `8025` is published on the host; SMTP `1025` stays inside the Compose network;
  - `notification-service`: host port `8088` for the actuator only. It waits for its Postgres and `kafka` to be *healthy*, and for `mailpit` to be *started*: the sender job retries while SMTP is not reachable yet, so Notification does not need Mailpit to be ready.
  - Configuration comes only from `notification-service/.env` (untracked); `.env.example` is complete.
- **Kubernetes** (`k8s/notification/`, mirroring `k8s/customer/`):
  - `db-deployment.yaml` (PVC + Postgres), `db-service.yaml`, `secret-template.yaml` (`notification-db-credentials`);
  - `app-config.yaml`: every non-secret variable of the spec's *Configuration* table, plus `JAVA_TOOL_OPTIONS`;
  - `app-deployment.yaml`: startup, liveness and readiness probes, the three `prometheus.io/*` annotations, `component: service`;
  - `app-service.yaml`: port 8088. No caller uses it; it is for `kubectl port-forward`.
  - **No app Secret:** Mailpit needs no SMTP credentials. `SPRING_MAIL_USERNAME` / `_PASSWORD` would go into one for a real provider (noted in the ConfigMap).
- **Mailpit placement: `k8s/notification/mailpit.yaml`** (Deployment + ClusterIP Service `mailpit`, ports 1025/8025, `/readyz` and `/livez` probes, `component: mail`). It lives there because Notification is its only client, the same reasoning as for its database, and so `kubectl apply -f k8s/notification/` deploys everything the service needs. The UI is reached with `kubectl port-forward -n voltstore svc/mailpit 8025:8025`, which is also in `k8s.txt`.
- **Network policies** (`k8s/network/network-policies.yaml`):
  - new `notification-db-from-notification-service` (5432) and `mailpit-smtp-from-notification-service` (1025 only from `app: notification-service`);
  - Notification → Kafka and Prometheus → Notification are already covered by the existing `component: service` rules;
  - the header comment lists the new `mail` component;
  - the default deny keeps everyone else away from both, and the Mailpit UI gets no rule (port-forward only).
- **`k8s.txt`:** `docker build` and `minikube image load` for `voltstore/notification-service:latest`, and the Mailpit port-forward.
- **Not verified in Kubernetes:** no cluster was running. The manifests follow `k8s/customer/` line by line, but `kubectl apply` was not run (a client dry run also needs a cluster).
- **Manual smoke test in Compose (2026-10-18):** `docker compose up -d --build notification-service`, then the topics created with `kafka-topics.sh` (Order and Delivery not running), then records published with `kafka-console-producer.sh --property parse.headers=true --property headers.delimiter='|' --property parse.key=true --property key.separator='|'` in the form `eventType:OrderPlaced|<orderId>|<json>`.
  1. On a new Postgres 18 volume, Flyway applied `V1` and readiness was `UP` about 10 s after start.
  2. `OrderPlaced` (catalogue payload) → "placed, awaiting payment" in Mailpit for `smoke@example.com`.
  3. The same record again → still one email, one row.
  4. `OrderDelivered` on `delivery.shipment-events` for that order → the "delivered" email at the same address; the row's recipient was resolved from the read model.
  5. With `docker stop mailpit`, a new `OrderShipped` row stayed `PENDING` with `smtp_unavailable: UnknownHostException: mailpit`, `attempts` 1 → 2 (30 s back-off). After `docker start mailpit` the email arrived on the next attempt (2 min back-off) and the row was `SENT`.
  - Mailpit keeps messages in memory, so its restart emptied the mailbox (noted in Compose and in `mailpit.yaml`).
- Scaling to 2 replicas was not tried; the concurrent claim is covered by `NotificationSenderJobIntegrationTest`.
