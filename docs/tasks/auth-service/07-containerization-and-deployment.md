---
status: TODO
service: auth-service
---
# Containerize and Deploy Auth Service

## Context
Like the other services, Auth Service must run in the Kubernetes setup alongside its own database, and be reachable by the API Gateway.
*References:* [Auth Service Spec](../../design/services-specs/auth-service-spec.md)

## Acceptance Criteria
- [ ] Add a Dockerfile consistent with the other services.
- [ ] Add Kubernetes manifests (Deployment, Service, ConfigMap/Secret) and a dedicated PostgreSQL instance/database, following the existing infrastructure layout.
- [ ] JWT signing key and DB credentials are supplied through a Secret, not baked into the image.
- [ ] Liveness/readiness probes are configured (Actuator health).
- [ ] Document how the gateway reaches `/auth/register` and `/auth/login` without authentication, and how it obtains the key (shared secret or public key) to validate tokens.

## Technical Notes / Constraints
- Inspect the existing Kubernetes folder before writing manifests and reuse its naming, labels, and structure.
- The gateway itself is outside this service's scope; this task only documents and prepares the contract Auth Service provides to it.
