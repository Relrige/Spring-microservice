# Services Requirements — Draft Notes

> Informal notes on emerging service-level requirements and technology decisions.

---

## API Gateway — Spring Cloud Gateway

**Decision:** The system will use **Spring Cloud Gateway** as the single entry point for all external client traffic.

**Responsibilities:**
- Route incoming requests to the appropriate microservice based on path.
- Validate JWT tokens at the edge — microservices receive trusted `X-User-Id` and `X-User-Role` headers and do not perform JWT validation themselves.
- Enforce coarse role-based access control per route (e.g., `/inventory/**` requires `ROLE_INVENTORY_WORKER`).

**Interaction with Kubernetes Ingress:**
- The K8s Ingress handles TLS termination and routes all external traffic to the Gateway service.
- Per-service routing logic lives in the Gateway config, not in the Ingress.

**Scope boundary:**
- The gateway handles client-to-service traffic only.
- Internal service-to-service calls bypass the gateway entirely.

**Deferred:** rate limiting, request/response transformation, and observability hooks — to be revisited during implementation.
