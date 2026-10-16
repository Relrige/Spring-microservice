---
status: TODO
service: customer-service
---
# Scaffold Customer Service Module

## Context
Customer Service does not exist in the repository yet. It owns two independent aggregates: `CustomerProfile` and `Cart` (the server-side persistence of the customer's cart). Before any endpoint can be built, the module needs to exist with the same conventions as the other services.
*References:* [Customer Service Spec](../../design/services-specs/customer-service-spec.md), [Auth scaffold task](../auth-service/01-scaffold-auth-service.md)

## Acceptance Criteria
- [ ] Create the `customer-service` Maven module as a sibling of the other services: same Spring Boot parent version, package root `ua.edu.ukma.springers.voltstore.customer`, same project layout as `inventory-service`.
- [ ] Add dependencies: Spring Web, Spring Data JPA, Validation, PostgreSQL driver, Flyway, Actuator, Spring Security, Lombok (as in the other services), and the Testcontainers PostgreSQL test dependencies. Kafka is added in [task 08](08-consume-order-paid.md).
- [ ] `application.yml` with datasource settings read from environment variables, `spring.jpa.hibernate.ddl-auto: validate`, Flyway enabled, and `.env.example` mirroring the other services. `.env` stays untracked.
- [ ] A dedicated PostgreSQL database for the service (database-per-service); no other service may access it.
- [ ] Application starts and connects to its database; a context-load smoke test (Testcontainers PostgreSQL) passes.

## Technical Notes / Constraints
- Copy the structure from an existing service (e.g. `inventory-service`) instead of inventing a new one.
- Port convention: container port `8080`; host / cluster port **`8087`** (8080–8086 are taken by the gateway and the other services). The Compose and Kubernetes wiring is [task 09](09-containerization-and-deployment.md).
- Store timestamps as `timestamptz` (`Instant`), as in the other services.
