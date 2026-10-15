---
status: TODO
service: delivery-service
---
# Align Containerization and Kubernetes Deployment

## Context
Delivery has a Dockerfile, a Compose entry and Kubernetes manifests from the scaffold, written for the draft (database only). The reworked service also needs Kafka, the internal token and the Inventory URL. This task checks that the complete service runs in Compose and Kubernetes the way Catalog and Inventory do, and unblocks the gateway routes.
*References:* [Catalog containerization task](../catalog-service/20-containerization-and-deployment.md), [Inventory containerization task](../inventory-service/18-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [ ] The image builds and starts in Compose after Postgres and Kafka are healthy; Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [ ] Secrets split as in the other services: `delivery-db-credentials` (database only) and `delivery-secrets` (internal token, app pod only), with templates in `k8s/delivery/`.
- [ ] ConfigMap and `.env.example` contain every variable from the spec's *Configuration* table, with comments; `.env` stays untracked.
- [ ] Liveness and readiness probes work and are not blocked by the security chain.
- [ ] Manual smoke test recorded in the task notes: publish an `OrderPaid` with `kafka-console-producer` (payload from the event catalogue, header `eventType: OrderPaid`) for a product with packaging in Inventory → the shipment becomes `TTN_GENERATED` and `TTNGenerated` appears on `delivery.shipment-events`; `GET /delivery/shipments` with the internal token shows it; an `OrderShipped` for the same order makes it `IN_TRANSIT`, and after `DELIVERY_CARRIER_MOCK_DELIVERY_DELAY` it is `DELIVERED` and `OrderDelivered` is published; restarting the pod while a shipment is `IN_TRANSIT` still ends in `DELIVERED`; `DELETE` on another shipment gives `CANCELLED`.
- [ ] Repeat with `DELIVERY_CARRIER_MOCK_FAILURE_RATE=1` → `TTN_FAILED` after the back-offs, then a manual retry with the rate back at `0` succeeds.
- [ ] `k8s.txt` build/load commands include `delivery-service` (verify, do not duplicate).
- [ ] Mark [API gateway task 10](../api-getaway/10-remaining-service-routes.md)'s Delivery part as unblocked.

## Technical Notes / Constraints
- One replica is enough; scaling to 2 is a useful check for the job's `SKIP LOCKED` claim and the outbox relay lock if time allows.
- A `NetworkPolicy` is a cross-service concern; mention it, do not create one only for Delivery.
- Update the spec's *Implementation Status* at sprint end.
