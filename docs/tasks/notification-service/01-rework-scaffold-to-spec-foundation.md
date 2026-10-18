---
status: TODO
service: notification-service
---
# Rework the Scaffold into the Spec Foundation

## Context
`notification-service` is a fresh Spring Initializr scaffold (Spring Boot 4.1.1, Java 25, starters for Kafka, Web MVC, Actuator and Validation, Testcontainers Kafka). The [Notification Service Spec](../../design/services-specs/notification-service-spec.md) (revised 2026-10-18) needs its own PostgreSQL database for the notification log and the recipient read model (D1, D2), and the module must look like the other services before features are added.

| Area | Scaffold | Spec / target |
|---|---|---|
| Root package | `ua.edu.ukma.springers.voltstore` | `ua.edu.ukma.springers.voltstore.notification`, like `...voltstore.customer` |
| Persistence | none | Spring Data JPA, PostgreSQL driver, Flyway; `ddl-auto: validate` |
| Tests | `TestcontainersConfiguration` (Kafka only) | `PostgresTestContainerConfig` (`postgres:18-alpine`) and `KafkaTestContainerConfig`, as in Customer |
| Configuration | `application.yaml` with the name only | `application.yml` with datasource, probes, problem details; `.env.example` |

This task adds no consumer and no job.
*References:* [Notification Spec — Service Overview, Configuration](../../design/services-specs/notification-service-spec.md), [Delivery foundation task](../delivery-service/01-rework-draft-to-spec-foundation.md), [Customer scaffold task](../customer-service/01-scaffold-customer-service.md)

## Acceptance Criteria
- [ ] Move the application class to `ua.edu.ukma.springers.voltstore.notification`; the packages used later are `config`, `entities`, `repositories`, `services`, `events`, `email`, `jobs`, `metrics`, `security`, `exceptions` (created by the tasks that fill them).
- [ ] `pom.xml` compared with `customer-service/pom.xml`: add `spring-boot-starter-data-jpa`, `postgresql` (runtime), `flyway-core` + `flyway-database-postgresql`, and the matching test starters and `testcontainers-postgresql`. Security, mail and Prometheus come with their own tasks (03, 08, 11).
- [ ] `application.yaml` renamed to `application.yml` (the other services' spelling): datasource from `SPRING_DATASOURCE_*`, `ddl-auto: validate`, `open-in-view: false`, problem details, liveness and readiness probes. Server port as in the other services (`8080` in containers).
- [ ] Test configuration: `PostgresTestContainerConfig` (`postgres:18-alpine`) and `KafkaTestContainerConfig` (`apache/kafka:4.1.0`) copied from Customer; the scaffold's `TestcontainersConfiguration` and `TestNotificationServiceApplication` are replaced or aligned with Customer's.
- [ ] A context-load test passes against Testcontainers PostgreSQL, with Flyway running an empty baseline (the first real migration comes in task 02).
- [ ] `.env.example` with the database variables and a comment that Kafka, SMTP and job variables follow in later tasks.

## Technical Notes / Constraints
- `HELP.md` and `.idea/` come from the generator. Delete `HELP.md` like the other modules did, and keep `.idea/` untracked (check `.gitignore`).
- `spring-boot-starter-webmvc` stays: there is no business API, but the actuator probes and `/actuator/prometheus` are served over HTTP.
- Run Maven only through `.\mvnw.cmd` in `notification-service/`.
