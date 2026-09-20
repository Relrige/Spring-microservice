# payment-service

## Purpose and boundary
Owns the payment bounded context: payment transactions (`PaymentTransaction`), bank acquirer integration via Anticorruption Layer (ACL), payment authorizations, and refunds/reversals.
Must not modify order item lines or reserve warehouse inventory.

## Checks
- Local directory check: `.\mvnw.cmd test-compile` (Windows) or `./mvnw test-compile` (Unix).
- Full tests: `.\mvnw.cmd test` (Windows) or `./mvnw test` (Unix) — requires running Docker for Testcontainers.

## Constraints
- Spring Boot 4.1.1, Java 25, Spring Data JPA, PostgreSQL, Flyway.
- Runs on port 8085 in Docker Compose.
