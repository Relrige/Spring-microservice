# catalog-service

## Purpose and boundary
Owns the catalog bounded context: product entities, specifications, categories, and public display pricing.
Must not manage inventory stock levels or create orders.

## Checks
- Local directory check: `.\mvnw.cmd test-compile` (Windows) or `./mvnw test-compile` (Unix).
- Full tests: `.\mvnw.cmd test` (Windows) or `./mvnw test` (Unix) — requires running Docker for Testcontainers.

## Constraints
- Spring Boot 4.1.1, Java 25, Spring Data JPA, PostgreSQL, Flyway.
- Runs on port 8081 in Docker Compose.
