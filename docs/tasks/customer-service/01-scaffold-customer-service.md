---
status: DONE
service: customer-service
---
# Scaffold Customer Service Module

## Context
Customer Service does not exist in the repository yet. It owns two independent aggregates: `CustomerProfile` and `Cart` (the server-side persistence of the customer's cart). Before any endpoint can be built, the module needs to exist with the same conventions as the other services.
*References:* [Customer Service Spec](../../design/services-specs/customer-service-spec.md), [Auth scaffold task](../auth-service/01-scaffold-auth-service.md)

## Acceptance Criteria
- [x] Create the `customer-service` Maven module as a sibling of the other services: same Spring Boot parent version, package root `ua.edu.ukma.springers.voltstore.customer`, same project layout as `inventory-service`.
- [x] Add dependencies: Spring Web, Spring Data JPA, Validation, PostgreSQL driver, Flyway, Actuator, Spring Security, Lombok (as in the other services), and the Testcontainers PostgreSQL test dependencies. Kafka is added in [task 08](08-consume-order-paid.md).
- [x] `application.yml` with datasource settings read from environment variables, `spring.jpa.hibernate.ddl-auto: validate`, Flyway enabled, and `.env.example` mirroring the other services. `.env` stays untracked.
- [x] A dedicated PostgreSQL database for the service (database-per-service); no other service may access it.
- [x] Application starts and connects to its database; a context-load smoke test (Testcontainers PostgreSQL) passes.

## Technical Notes / Constraints
- Copy the structure from an existing service (e.g. `inventory-service`) instead of inventing a new one.
- Port convention: container port `8080`; host / cluster port **`8087`** (8080–8086 are taken by the gateway and the other services). The Compose and Kubernetes wiring is [task 09](09-containerization-and-deployment.md).
- Store timestamps as `timestamptz` (`Instant`), as in the other services.

## Implementation Notes (2026-10-16)
- **Module:** generated with Spring Initializr, then aligned with `delivery-service`: `artifactId` `customer-service`, Spring Boot 4.1.1, Java 25, `maven-surefire-plugin` 3.5.6 with `user.timezone=Europe/Kyiv`, Spring Security and its test starter added.
- **Deviation (Kafka in task 01):** `spring-boot-starter-kafka` and its test starter were already in the generated `pom.xml` and stay there. Kafka is only configured and used in task 08; without listeners or topic beans nothing connects to a broker, so the service starts without Kafka until then.
- **Test containers:** split into `PostgresTestContainerConfig` (`postgres:18-alpine`) and `KafkaTestContainerConfig` (`apache/kafka:4.1.0`, topic auto-creation off), as in Delivery, so tests that only need the database do not start Kafka. Images are pinned instead of `latest`.
- **`application.yml`:** `ddl-auto: validate`, Problem Details on, `accept-float-as-int: false`, health probes, and `open-in-view: false` (all reads happen in service transactions). The datasource comes from `SPRING_DATASOURCE_*` (see `.env.example`); there is no migration yet, Flyway runs with an empty location.
- **Verified (2026-10-16):** `CustomerServiceApplicationTests` (context load against Testcontainers PostgreSQL) passes.
