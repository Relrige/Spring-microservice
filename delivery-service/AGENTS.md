# delivery-service

## Purpose and boundary
Owns the delivery bounded context: parcel shipments, recipient snapshot handling, postal carrier integration (e.g. Nova Poshta, Meest) via Anticorruption Layer (ACL), and waybill (TTN) generation.
Must not process payment transactions or directly adjust warehouse inventory.

## Checks
- Local directory check: `.\mvnw.cmd test-compile` (Windows) or `./mvnw test-compile` (Unix).
- Full tests: `.\mvnw.cmd test` (Windows) or `./mvnw test` (Unix) — requires running Docker for Testcontainers.

## Constraints
- Spring Boot 4.1.1, Java 25, Spring Data JPA, PostgreSQL, Flyway.
- Runs on port 8083 in Docker Compose.
