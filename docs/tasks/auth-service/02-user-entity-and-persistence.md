---
status: TODO
service: auth-service
---
# User Entity, Role Enum, Repository and Migration

## Context
The service owns exactly one entity: `User` (`id`, `email`, `passwordHash`, `role`, `createdAt`). It needs a persistent model and a schema managed by Flyway.
*References:* [Auth Service Spec — Service Overview](../../design/services-specs/auth-service-spec.md)

## Acceptance Criteria
- [ ] Create `Role` enum with `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN`.
- [ ] Create `User` JPA entity with `id` (UUID), `email`, `passwordHash`, `role` (stored as string), `createdAt`.
- [ ] Create Flyway migration `V1__create_users_table.sql` with a primary key and a **unique constraint on `email`**.
- [ ] Create `UserRepository` with `findByEmail` and `existsByEmail`.
- [ ] Repository test (against a real PostgreSQL, e.g., Testcontainers) verifies persistence, lookup by email, and that a duplicate email violates the unique constraint.

## Technical Notes / Constraints
- Use `@Enumerated(EnumType.STRING)`, never ordinal, for `role`.
- Normalize email (trim + lowercase) in a single place before it is stored or queried, otherwise `A@x.com` and `a@x.com` become two accounts. Decide where this lives (service layer vs. entity callback) and document it.
- The DB unique constraint is the real guard against duplicates; the pre-check in the service only produces a friendly error (see the registration task for the race condition).
- Hibernate `ddl-auto` should be `validate` or `none`; Flyway owns the schema.
