---
status: TODO
service: order-service
---
# Align Containerization and Kubernetes Deployment

## Context
Order Service has a Dockerfile, a Compose entry and Kubernetes manifests written for the draft. The reworked service needs a Flyway schema, the shared `INTERNAL_TOKEN`, Kafka and the URLs of four services. This task verifies the whole checkout in Compose (and Kubernetes) with the real neighbours, which is also the first end-to-end run for Inventory's, Delivery's and Customer's Order consumers.
*References:* [Inventory containerization task](../inventory-service/18-containerization-and-deployment.md), [Payment containerization task](../payment-service/09-containerization-and-deployment.md), [Gateway routes task](../api-getaway/10-remaining-service-routes.md)

## Acceptance Criteria
- [ ] The image builds and starts in Compose; Flyway applies all migrations on an empty Postgres 18 volume; Hibernate `validate` passes.
- [ ] Secrets split as in the other services: `order-db-credentials` (database) and `order-secrets` (`INTERNAL_TOKEN`, app pod only); `k8s/order/app-secret-template.yaml` added; the deployment loads the ConfigMap and both secrets.
- [ ] `docker-compose.yml`, `.env.example` and the ConfigMap contain every variable of the spec's *Configuration* table, with comments (`PAYMENT_READ_TIMEOUT` ≥ 6 s).
- [ ] Probes work and are not blocked by security.
- [ ] Gateway routes for Order added ([gateway task 10](../api-getaway/10-remaining-service-routes.md)): customer routes `Required` + `CUSTOMER`, worker routes `INVENTORY_WORKER`.
- [ ] **End-to-end smoke test through the gateway** with a registered customer's JWT: validate → submit (`201`, stock reserved in Inventory) → pay with `4242` (`PAID`; Delivery creates a shipment; Customer clears the cart) → `TTNGenerated` moves the order to `PENDING_SHIPPING` → worker ships (`SHIPPED`; Inventory consumes the reservation) → the mock carrier reports delivery (`DELIVERED`). Plus: pay with `0002` → `402`; cancel a paid order → stock released, shipment cancelled, refund made; an unpaid order expires; without a token every `/orders` route is `401`; another customer's order is `404`.
- [ ] The old Compose volume of the draft (`ddl-auto: update` table) is reset before the run (a destructive step for the team, as in Payment task 09).

## Technical Notes / Constraints
- The run needs Auth ([task 09](../auth-service/09-email-claim-in-jwt.md)), the gateway ([task 11](../api-getaway/11-forward-user-email-header.md)) and Catalog ([task 24](../catalog-service/24-stock-status-in-batch-get.md)) done first.
- Use an isolated Compose project for the run, so existing containers and volumes are not touched.
