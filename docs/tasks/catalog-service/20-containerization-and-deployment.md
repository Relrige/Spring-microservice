---
status: TODO
service: catalog-service
---
# Align Containerization and Kubernetes Deployment

## Context
Catalog Service already has a Dockerfile, Compose entry and Kubernetes manifests, written for the draft. The reworked service adds Flyway-managed schema, security configuration, and the shared `INTERNAL_TOKEN`. This task checks that the complete service apart from the inter-service call (tasks 01–19) really runs in Compose and Kubernetes the same way auth-service does.
*References:* [Auth Service containerization task](../auth-service/07-containerization-and-deployment.md), [Security Model — Preconditions](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [ ] The image builds and starts in Docker Compose; Flyway applies V1/V2 against the empty Postgres 18 volume and Hibernate `validate` passes.
- [ ] Secrets are split like in auth-service: `catalog-db-credentials` (database only, used by the app and the Postgres pod) and `catalog-secrets` (`INTERNAL_TOKEN`, used only by the app pod). Update `secret-template.yaml` and add an app secret template.
- [ ] `k8s/catalog/app-deployment.yaml` loads both secrets and the ConfigMap; no secret values are in ConfigMaps or images.
- [ ] Liveness/readiness probes still work against Actuator and are not blocked by the security chain.
- [ ] `docker-compose.yml` and `catalog-service/.env.example` contain every variable the application needs, with comments; `.env` stays untracked.
- [ ] Manual smoke test recorded in the PR: create a category and a product with the `X-User-Id` / `X-User-Role` headers set by hand, list products anonymously, and confirm `denyAll()` blocks unknown routes.
- [ ] `k8s.txt` build/load commands still include `catalog-service` (already present; verify, do not duplicate).

## Technical Notes / Constraints
- Catalog uses `replicas: 2` while auth uses 1. Two replicas share one database, which matters later for the outbox relay ([task 19](19-transactional-outbox.md)) and for Flyway (only one instance should apply migrations at a time; Flyway's lock handles that, but note it).
- Ingress routing for the catalog is left to the API Gateway work. The current Ingress points straight at services; do not extend it.
- `NetworkPolicy` restricting ingress to the gateway and other services is a cross-service concern from the Security Model; if it is not in place yet, add a note to the PR instead of creating a policy only for this service.
- The Kafka variables were added in [task 10](10-kafka-spring-setup-and-topic.md); verify here that the whole stack (broker, Postgres, catalog) starts together in Compose and in minikube, and that the outbox relay ([task 19](19-transactional-outbox.md)) works with two replicas.
- `INVENTORY_SERVICE_URL` is added in [task 21](21-inventory-readiness-client.md).
