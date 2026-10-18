---
status: TODO
service: notification-service
---
# Containerization, Compose and Kubernetes Deployment (with Mailpit)

## Context
Unlike the other services, Notification has no Dockerfile, Compose entry or Kubernetes manifests from the scaffold. It also needs a new infrastructure piece, **Mailpit**, as the SMTP server (D4). This task makes the complete service run in Compose and Kubernetes the way Customer and Delivery do.
*References:* [Delivery containerization task](../delivery-service/16-containerization-and-deployment.md), [Customer containerization task](../customer-service/09-containerization-and-deployment.md), [Notification Spec — Configuration](../../design/services-specs/notification-service-spec.md)

## Acceptance Criteria
- [ ] `Dockerfile` copied from Customer's (multi-stage Maven build, layered extract, distroless Java 25, non-root user 10001, heap from `JAVA_TOOL_OPTIONS`).
- [ ] Compose:
  - `notification-service-postgres` (Postgres 18, own volume);
  - `mailpit` (pinned tag; SMTP 1025 internal, UI 8025 published on the host for demos);
  - `notification-service` waiting for its Postgres, `kafka` and `mailpit`.
  Configuration only from `notification-service/.env` (untracked; `.env.example` complete).
- [ ] Kubernetes in `k8s/notification/`, mirroring `k8s/customer/`:
  - database Deployment and Service, `notification-db-credentials` template;
  - app ConfigMap with every non-secret variable from the spec's *Configuration* table;
  - app Deployment with liveness and readiness probes and the three `prometheus.io/*` annotations, and its Service.
  No app secret is needed while SMTP has no credentials; say so in the notes.
- [ ] Mailpit in Kubernetes: Deployment and Service `mailpit` (ports 1025 and 8025, ClusterIP; UI through `kubectl port-forward`). Decide whether it lives in `k8s/notification/` or a shared folder, and record the choice.
- [ ] `k8s/network/network-policies.yaml`: Notification → its database, Notification → Mailpit and Kafka, Prometheus → Notification, following the existing policies. Nothing else may reach Notification's database or Mailpit's SMTP port.
- [ ] `k8s.txt` builds and loads `voltstore/notification-service:latest`.
- [ ] Manual smoke test recorded in the notes:
  1. on an empty Postgres 18 volume, Flyway applies the migrations and readiness is `UP`;
  2. publish an `OrderPlaced` with `kafka-console-producer` (catalogue payload, header `eventType: OrderPlaced`) → the email appears in the Mailpit UI;
  3. the same record again → no second email;
  4. an `OrderDelivered` on `delivery.shipment-events` for that order → the "delivered" email arrives at the same address;
  5. with Mailpit stopped, a new event leaves the row `PENDING` with growing `attempts`; after Mailpit restarts, the email arrives.

## Technical Notes / Constraints
- Compose does not create topics automatically. Create Order's and Delivery's topics with `kafka-topics.sh` when their services are not running (see the Delivery smoke test).
- One replica is enough. Scaling to 2 is a good check of the job's `SKIP LOCKED` claim if time allows.
- Update the spec's *Implementation Status* at sprint end.
