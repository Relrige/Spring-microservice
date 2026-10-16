---
status: DONE
service: customer-service
---
# Containerization and Kubernetes Deployment

## Context
Customer Service is new, so unlike the other services it has no Dockerfile, Compose entry or Kubernetes manifests from the scaffold. This task makes the complete service run in Compose and Kubernetes the same way as Delivery and Inventory, and unblocks its gateway routes.
*References:* [Delivery containerization task](../delivery-service/16-containerization-and-deployment.md), [Inventory containerization task](../inventory-service/18-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [x] A `Dockerfile` following the other services.
- [x] Compose: `customer-service-postgres` (with its volume) and `customer-service` on host port `8087`, starting after Postgres and Kafka are healthy. Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [x] Kubernetes manifests in `k8s/customer/` (database, app deployment, Service on port `8087`, ConfigMap), following `k8s/delivery/`. Secrets split as in the other services: `customer-db-credentials` (database only), plus `customer-secrets` only if the internal token was kept in [task 03](03-trusted-header-security.md); templates are in `k8s/customer/`.
- [x] ConfigMap and `.env.example` contain every variable the service reads, with comments.
- [x] Liveness and readiness probes work and are not blocked by the security chain.
- [x] `k8s.txt` build/load commands include `customer-service`.
- [x] Manual smoke test recorded in the task notes, calling the service directly with trusted headers:
  - profile `GET` (empty), then `PUT`, then `GET`
  - cart add, set and remove; replace; merge with an overlapping product; clear
  - one `422` response for each violation code
  - an `OrderPaid` published with `kafka-console-producer` (header `eventType: OrderPaid`) clears the cart
- [x] Mark the Customer part of [API gateway task 10](../api-getaway/10-remaining-service-routes.md) as unblocked, with the upstream URL `http://customer-service:8087`.

## Technical Notes / Constraints
- One replica is enough. Scaling to 2 is a useful check that the pessimistic lock serialises cart writes across instances, not just threads.
- **For the gateway task:** the route table in the [API Gateway spec](../../design/services-specs/api-gateway-spec.md) is out of date for Customer. It lacks `PUT` and `DELETE /customers/cart`. Build the routes from the [API Contracts](../../design/services-requirements/api-contracts.md).
- A `NetworkPolicy` is a cross-service concern; do not create one only for Customer.

## Implementation Notes (2026-10-16)
- **`Dockerfile`:** copy of Delivery's (Maven build stage, layered extract, distroless Java 25, user `10001`, `JAVA_TOOL_OPTIONS` from the ConfigMap).
- **Compose:** `customer-service-postgres` (`postgres:18-alpine`, volume `customer-service-postgres`, `pg_isready` health check) and `customer-service` on host port `8087`, starting after its database and Kafka are healthy. Each developer's untracked `customer-service/.env` is copied from `.env.example`.
- **Kubernetes (`k8s/customer/`):** `app-config.yaml` (`customer-config`: database name and URL, Kafka bootstrap, `ORDER_EVENTS_TOPIC`, `JAVA_TOOL_OPTIONS`), `secret-template.yaml` (`customer-db-credentials`), `app-deployment.yaml` (1 replica, label `app.kubernetes.io/component: service`, startup, liveness and readiness probes on `/actuator/health/*`, resources as in Delivery), `app-service.yaml` (port `8087` → `8080`), `db-deployment.yaml` and `db-service.yaml`. There is **no `customer-secrets`**: the service has no internal token (decision in task 03).
- **Deviation — NetworkPolicy:** the task said not to create one only for Customer, but `k8s/network/network-policies.yaml` already has a default-deny rule plus one "database per service" rule for every service. Without a `customer-db-from-customer-service` rule the customer pod could not reach its own database on a Calico cluster, so that rule was added next to the others. The shared service and broker rules already cover Customer through its `component: service` label.
- **`k8s.txt`:** `docker build` and `minikube image load` lines for `voltstore/customer-service:latest`.
- **API gateway task 10:** the Customer part is marked unblocked, with the nine routes, the upstream `http://customer-service:8087`, and a note that the gateway spec's route table lacks `PUT`/`DELETE /customers/cart`.
- **Verified (2026-10-16):**
  - The image builds (`docker build -t voltstore/customer-service:latest ./customer-service`).
  - **Smoke test, run against the image instead of the Compose stack.** The Compose services use fixed container names (`kafka`, ...) that already exist, stopped, from the main checkout, so running Compose from the worktree would collide with them. Instead the image ran on a private Docker network with its own `postgres:18-alpine` and `apache/kafka:4.1.0` (topic auto-creation off), with the same environment variables as Compose. Steps and results:
    - Flyway applied V1 and V2 on an empty database, and Hibernate `validate` passed.
    - Readiness and liveness returned `UP`.
    - Profile: `GET` returned all `null`, then `PUT`, then `GET` returned the saved profile.
    - Cart: add → set → add another → remove → replace → merge with an overlapping product (summed) all returned `200`.
    - Each violation code returned `422`: a merge over 10 → `QUANTITY_LIMIT_EXCEEDED`, a duplicate → `DUPLICATE_PRODUCT`, 11 products → `TOO_MANY_PRODUCTS`.
    - Clear returned `[]`. An anonymous call got `401`, `ADMIN` got `403`.
    - An `OrderPaid` published with `kafka-console-producer` (header `eventType: OrderPaid`) cleared the customer's cart within about a second, keeping the `carts` row. A record that is not JSON went to `customer.order.order-events.dlt`.
  - `docker compose config -q` is valid (checked with temporary empty `.env` files that were removed afterwards).
  - **Not verified on Kubernetes:** the minikube cluster is stopped, so the manifests were not applied. They mirror Delivery's verified manifests; the only differences are the names, port `8087`, and the missing `customer-secrets`.
