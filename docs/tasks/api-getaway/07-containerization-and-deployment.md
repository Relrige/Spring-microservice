---
status: DONE
service: api-gateway
---
# Containerize and Deploy API Gateway

## Context
The gateway must run next to the other services locally (docker-compose) and in Kubernetes, with its own configuration and secret.
*References:* [API Gateway Spec — Configuration](../../design/services-specs/api-gateway-spec.md#configuration), [Containerize and Deploy Auth Service](../auth-service/07-containerization-and-deployment.md), [Inventory containerization](../inventory-service/18-containerization-and-deployment.md)

**Revised 2026-10-14** after the Compose/Kubernetes clean-up (`8f84dd8`, `d16b2a8`) and the manual e2e runbooks in `e2e/manual/`: JVM flags moved out of the Dockerfiles into `JAVA_TOOL_OPTIONS`, Deployments got a `startupProbe`, the JWT key is called `AUTH_JWT_SECRET` (property `auth.jwt.secret`, task 02), and inside Compose services reach each other on the **container** port `8080`, not on the Kubernetes Service ports.

## Acceptance Criteria
- [x] Dockerfile consistent with the other services: same multi-stage layered build, `ENTRYPOINT` without JVM flags (heap and GC come from `JAVA_TOOL_OPTIONS`).
- [x] Kubernetes manifests under `k8s/gateway/`: Deployment, ClusterIP Service `api-gateway` (port `8080`), ConfigMap `gateway-config`, Secret template `gateway-secrets`. Same naming, `runAsNonRoot` security context, resources, `startupProbe`/`livenessProbe`/`readinessProbe` and `JAVA_TOOL_OPTIONS` as `k8s/auth/`. No database manifests.
- [x] `AUTH_JWT_SECRET` comes from the `gateway-secrets` Secret, never from the image or the ConfigMap; the template says that the value must equal the one in `auth-secrets`. Upstream URLs (`GATEWAY_UPSTREAMS_*`) come from the ConfigMap and use the Kubernetes Service ports (auth `8086`, catalog `8081`, inventory `8084`).
- [x] Actuator is served on a separate management port (`8090`) that is neither published by Compose nor exposed by the `api-gateway` Service; the probes use it. This keeps `/actuator/**` off the public surface (it is `404` on port `8080`, see [task 09](09-end-to-end-verification.md)).
- [x] `docker-compose.yml`: `api-gateway` service built from `./api-gateway`, published on host port `8080`, `env_file: api-gateway/.env`. Upstream URLs use Compose service names and the container port (`http://auth-service:8080`, `http://catalog-service:8080`, `http://inventory-service:8080`), documented in `api-gateway/.env.example`.
- [x] The gateway starts without waiting for its upstreams (no `depends_on`; it is stateless).
- [x] `k8s.txt`: build and `minikube image load` commands for `voltstore/api-gateway:latest`, and the gateway in the "minimal stack" notes.

## Technical Notes / Constraints
- Inspect `k8s/` first and reuse its layout instead of inventing a new one.
- The same `AUTH_JWT_SECRET` value must exist in two Secrets (`auth-secrets` and `gateway-secrets`) and in two `.env` files. Say so in the templates.
- Ingress and NetworkPolicy changes are a separate task: [08](08-ingress-and-trust-boundary.md).
- Running the stack and checking requests end to end is [task 09](09-end-to-end-verification.md).

## Progress Notes (2026-10-14)
- Dockerfile identical to auth-service's (layered build, distroless, `JAVA_TOOL_OPTIONS` from the ConfigMap).
- `management.server.port: 8090`: probes in `k8s/gateway/app-deployment.yaml` use the named port `management`; the Service exposes only `8080`; Compose publishes only `8080`. `ManagementPortTest` proves `/actuator/health/*` answers on the management port and is `404` on the public port. Tests disable the management server (`management.server.port=-1`) except in that class.
- `spring.application.name` corrected to `api-gateway` (it appears in every log line).
- Compose check (2026-10-14): `docker compose up -d --build kafka api-gateway auth-service catalog-service inventory-service` builds and starts the gateway next to the services; it starts immediately (no `depends_on`) and its readiness does not depend on upstreams. The full request checks are recorded in [task 09](09-end-to-end-verification.md).
