---
status: DONE
service: catalog-service
---
# Category Entity, Repository and Migration

## Context
`Category` (`id`, `name`) is the simpler of the two owned entities, and `Product` references it. Categories are created first so that the product migration can declare a real foreign key.
*References:* [Catalog Spec — Service Overview](../../design/services-specs/catalog-service-spec.md), [Domain Entities — Catalog](../../design/entities/entities.md)

## Acceptance Criteria
- [x] Create `Category` JPA entity with `id` (UUID) and `name`.
- [x] Flyway migration `V1__create_categories_table.sql` with a primary key and a **case-insensitive unique index on `lower(name)`** (names are unique: `Laptops` and `laptops` cannot coexist).
- [x] `CategoryRepository` with `existsByName…`-style lookups needed by create and rename (see tasks 05 and 06).
- [x] Repository test (real PostgreSQL via Testcontainers): persistence, lookup by name, and a duplicate name violates the unique constraint.

## Technical Notes / Constraints
- **Decision (made):** category names are unique and the comparison is case-insensitive. The name is trimmed before it is stored or compared, and the database enforces uniqueness with a unique index on `lower(name)`. The service-layer pre-check (create and rename) must use the same rule (for example `existsByNameIgnoreCase`), so the friendly error and the database constraint always agree. The repository test must prove that `Laptops` then `laptops` violates the index.
- The DB constraint is the real guard against duplicates; the service pre-check only produces a friendly error (the race is handled in [task 05](05-create-category.md)).
- Generate the ID in the application (UUID), because `Product.id`/`Category.id` are referenced by other services and by events.
- Hibernate `ddl-auto` stays `validate`; Flyway owns the schema.
- Use the entity convention chosen in [task 01](01-rework-draft-to-spec-foundation.md) (no `@Data`).
- **ID generation (decision):** `@GeneratedValue(strategy = GenerationType.UUID)`, the same as `auth-service`. Hibernate generates the UUID in the application when the entity is persisted, not the database.
- **Name length (decision):** `VARCHAR(100)`, exposed as `Category.NAME_MAX_LENGTH` and reused by the DTO's `@Size`.
- Normalization (trim; case kept as typed) lives only in `CategoryNameNormalizer`. The repository pre-checks use explicit `lower(...)` JPQL queries rather than derived `IgnoreCase` methods (which use `upper(...)`), so they exactly mirror the `categories_name_lower_key` index.
