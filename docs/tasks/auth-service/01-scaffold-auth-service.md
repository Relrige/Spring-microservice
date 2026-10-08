---
status: DONE
service: auth-service
---
# Scaffold Auth Service Module

## Context
The Auth Service does not exist yet in the repository. It is the identity master for all roles and the only service that issues JWTs. Before any endpoint can be built, the module needs to exist with the same conventions as the other services.
*References:* [Auth Service Spec](../../design/services-specs/auth-service-spec.md)

## Acceptance Criteria
- [x] Create the `auth-service` Maven module as a sibling of the other services, following their package naming and project layout.
- [x] Add dependencies: Spring Web, Spring Data JPA, Validation, PostgreSQL driver, Flyway, Lombok (if used elsewhere), and Spring Security Crypto (for BCrypt).
- [x] Add `application.yml` with datasource settings read from environment variables, mirroring the `.env` approach of the other services.
- [x] Add a dedicated PostgreSQL database for the service (database-per-service); no other service may access it.
- [x] Application starts and connects to its database; a context-load smoke test passes.

## Technical Notes / Constraints
- Compare with an existing service (e.g., `catalog-service`) and reuse its structure instead of inventing a new one.
- Decide whether to depend on the full Spring Security starter or only `spring-security-crypto`. This service has no authenticated endpoints (the gateway performs token checks), so the lighter option is enough; record the choice if it is non-obvious.
