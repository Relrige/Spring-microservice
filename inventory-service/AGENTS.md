# inventory-service

## Purpose and boundary
Owns the inventory bounded context: warehouse stock items, physical dimensions/weights, and stock reservations (`StockReservation`) with expiration/rollback capability.
Must not manage user shopping carts or process billing charges.

## Checks
- Local directory check: `.\mvnw.cmd test-compile` (Windows) or `./mvnw test-compile` (Unix).
- Full tests: `.\mvnw.cmd test` (Windows) or `./mvnw test` (Unix) — requires running Docker for Testcontainers.

## Constraints
- Spring Boot 4.1.1, Java 25, Spring Data JPA, PostgreSQL, Flyway.
- Runs on port 8084 in Docker Compose.
