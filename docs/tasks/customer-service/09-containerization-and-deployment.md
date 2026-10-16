---
status: TODO
service: customer-service
---
# Containerization and Kubernetes Deployment

## Context
Customer Service is new, so unlike the other services it has no Dockerfile, Compose entry or Kubernetes manifests from the scaffold. This task makes the complete service run in Compose and Kubernetes the same way as Delivery and Inventory, and unblocks its gateway routes.
*References:* [Delivery containerization task](../delivery-service/16-containerization-and-deployment.md), [Inventory containerization task](../inventory-service/18-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [ ] A `Dockerfile` following the other services.
- [ ] Compose: `customer-service-postgres` (with its volume) and `customer-service` on host port `8087`, starting after Postgres and Kafka are healthy. Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [ ] Kubernetes manifests in `k8s/customer/` (database, app deployment, Service on port `8087`, ConfigMap), following `k8s/delivery/`. Secrets split as in the other services: `customer-db-credentials` (database only), plus `customer-secrets` only if the internal token was kept in [task 03](03-trusted-header-security.md); templates are in `k8s/customer/`.
- [ ] ConfigMap and `.env.example` contain every variable the service reads, with comments.
- [ ] Liveness and readiness probes work and are not blocked by the security chain.
- [ ] `k8s.txt` build/load commands include `customer-service`.
- [ ] Manual smoke test recorded in the task notes, calling the service directly with trusted headers:
  - profile `GET` (empty), then `PUT`, then `GET`
  - cart add, set and remove; replace; merge with an overlapping product; clear
  - one `422` response for each violation code
  - an `OrderPaid` published with `kafka-console-producer` (header `eventType: OrderPaid`) clears the cart
- [ ] Mark the Customer part of [API gateway task 10](../api-getaway/10-remaining-service-routes.md) as unblocked, with the upstream URL `http://customer-service:8087`.

## Technical Notes / Constraints
- One replica is enough. Scaling to 2 is a useful check that the pessimistic lock serialises cart writes across instances, not just threads.
- **For the gateway task:** the route table in the [API Gateway spec](../../design/services-specs/api-gateway-spec.md) is out of date for Customer. It lacks `PUT` and `DELETE /customers/cart`. Build the routes from the [API Contracts](../../design/services-requirements/api-contracts.md).
- A `NetworkPolicy` is a cross-service concern; do not create one only for Customer.
