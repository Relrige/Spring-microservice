---
status: DONE
service: api-gateway
---
# Scaffold API Gateway Module

## Context
The API Gateway does not exist yet. It is the single entry point for client traffic, owns no data and has no database. Before routing and security can be built, the module must exist with the same conventions as the other services.
*References:* [API Gateway Spec](../../design/services-specs/api-gateway-spec.md)

## Acceptance Criteria
- [x] Create the `api-gateway` Maven module as a sibling of the other services, following their package naming and project layout (check `auth-service`).
- [x] Add dependencies: Spring Cloud Gateway Server WebMVC, Actuator, `nimbus-jose-jwt` (same library as auth-service, used by [task 02](02-jwt-verification.md)) and test starters. No JPA, Flyway or PostgreSQL.
- [x] Import the Spring Cloud BOM version that matches the Spring Boot version used by the other services.
- [x] Add `application.yml` with the container port consistent with the other services (`8080`), health probes enabled (`/actuator/health/liveness`, `/actuator/health/readiness`), and a `.env.example`.
- [x] Application starts without any database; a context-load smoke test passes.

## Technical Notes / Constraints
- **Gateway variant (decision):** Server WebMVC is used so the gateway shares the servlet stack, testing style (MockMvc) and mental model of the other services. It supports YAML route definitions, `Path`/`Method` predicates (with regex path variables), request-header filters and custom handler filter functions, which covers every feature in the spec. If a missing feature is discovered later, record it as a trade-off against the WebFlux variant.
- Spring Cloud Gateway MVC property names changed between Spring Cloud releases (`spring.cloud.gateway.mvc.*` vs `spring.cloud.gateway.server.webmvc.*`); check the documentation of the release you import.
- Reuse the structure of `auth-service` instead of inventing a new one. Decide whether the Spring Security starter is needed; if the gateway does its own JWT check in a filter, it is not, and the choice should be recorded.
