---
status: TODO
service: inventory-service
---
# Align Containerization and Kubernetes Deployment

## Context
Inventory Service already has a Dockerfile, Compose entry and Kubernetes manifests written for the draft. The reworked service adds Flyway-managed schema, security configuration, the shared `INTERNAL_TOKEN` and the Kafka connection. This task verifies that the complete service apart from the Catalog call (tasks 01–17) runs in Compose and Kubernetes the same way auth-service does.
*References:* [Auth Service containerization task](../auth-service/07-containerization-and-deployment.md), [Catalog containerization task](../catalog-service/20-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [ ] The image builds and starts in Docker Compose; Flyway applies all migrations on an empty Postgres 18 volume and Hibernate `validate` passes.
- [ ] Secrets follow the auth-service split: `inventory-db-credentials` (database only, used by the app and Postgres pod) and `inventory-secrets` (`INTERNAL_TOKEN`, app pod only). Update `secret-template.yaml` and add an app secret template.
- [ ] `k8s/inventory/app-deployment.yaml` loads both secrets and the ConfigMap; no secret values in ConfigMaps or images.
- [ ] Probes still work against Actuator and are not blocked by the security chain.
- [ ] `docker-compose.yml` and `.env.example` contain every required variable (database, `INTERNAL_TOKEN`, Kafka bootstrap servers, topic names), with comments.
- [ ] Manual smoke test recorded in the PR with hand-set trusted headers: adjust stock, set packaging, call readiness with the internal token, reserve and release a reservation; confirm events appear on the topic and unknown routes are denied.
- [ ] With two replicas running, the outbox relay keeps per-product event order ([task 17](17-transactional-outbox.md)) and both replicas consume `ProductCreated` / `OrderShipped` as one consumer group (each message handled once).

## Technical Notes / Constraints
- With `replicas: 2` and a topic of 3 partitions, partitions are divided between the two consumers of the group; verify this in the broker's consumer-group description.
- Ingress routing is left to the API Gateway work; do not extend the current Ingress.
- `NetworkPolicy` is a cross-service concern from the Security Model; if it is not in place yet, note it in the PR instead of creating a single-service policy.
- `INVENTORY_SERVICE_URL`-style settings for the Catalog client are added in [task 19](19-catalog-product-existence-client.md).
