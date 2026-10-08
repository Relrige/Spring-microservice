---
status: DONE
service: auth-service
---
# Containerize and Deploy Auth Service

## Context
Like the other services, Auth Service must run in the Kubernetes setup alongside its own database, and be reachable by the API Gateway.
*References:* [Auth Service Spec](../../design/services-specs/auth-service-spec.md)

## Acceptance Criteria
- [x] Add a Dockerfile consistent with the other services.
- [x] Add Kubernetes manifests (Deployment, Service, ConfigMap/Secret) and a dedicated PostgreSQL instance/database, following the existing infrastructure layout.
- [x] JWT signing key and DB credentials are supplied through Secrets, not baked into the image. Two secrets: `auth-db-credentials` (database only, used by the app and the Postgres pod) and `auth-secrets` (`JWT_SECRET`, `ADMIN_PASSWORD`, `INTERNAL_TOKEN`, used only by the app pod, so the database container never receives them).
- [x] Liveness/readiness probes are configured (Actuator health).
- [x] Auth service is included in `docker-compose.yml` (service + PostgreSQL, port 8086) and in the build/load commands of `k8s.txt`.

## Technical Notes / Constraints
- Ingress routing for auth is intentionally not added yet; it will be handled together with the API Gateway.
- The gateway contract document (public routes, token validation key) is still to be written after the remaining auth-service work.
- Inspect the existing Kubernetes folder before writing manifests and reuse its naming, labels, and structure.
- The gateway itself is outside this service's scope; this task only documents and prepares the contract Auth Service provides to it.
