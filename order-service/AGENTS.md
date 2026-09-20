# order-service

## Purpose and boundary
Owns the order bounded context: order lifecycle management, total amount calculation, order item snapshots (`OrderItemSnapshot`), delivery snapshots (`DeliverySnapshot`), and coordination of saga rollbacks on payment/inventory failure.
Must not directly mutate catalog base prices or directly manage carrier waybills.

## Checks
- Local directory check: `.\mvnw.cmd test-compile` (Windows) or `./mvnw test-compile` (Unix).
- Full tests: `.\mvnw.cmd test` (Windows) or `./mvnw test` (Unix) — requires running Docker for Testcontainers.

## Constraints
- Spring Boot 4.1.1, Java 25, Spring Data JPA, PostgreSQL, Flyway.
- Runs on port 8082 in Docker Compose.
