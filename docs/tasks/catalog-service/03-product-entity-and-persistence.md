---
status: DONE
service: catalog-service
---
# Product Entity, Status Enums, Repository and Migration

## Context
`Product` is the master record that every other service references by `id`. It carries the manager-controlled `status` and the asynchronously maintained `stockStatus` read-model. Catalog never stores quantities or packaging data.
*References:* [Catalog Spec — Service Overview](../../design/services-specs/catalog-service-spec.md), [Domain Entities — Catalog](../../design/entities/entities.md), [Category persistence](02-category-entity-and-persistence.md)

## Acceptance Criteria
- [x] Create enums `ProductStatus { ACTIVE, NOT_ACTIVE }` and `StockStatus { IN_STOCK, OUT_OF_STOCK }`.
- [x] Create `Product` entity: `id` (UUID), `title`, `description`, `categoryId`, `basePrice`, `status`, `stockStatus`. Enums are stored as strings.
- [x] Flyway migration `V2__create_products_table.sql`: primary key; **foreign key** `category_id → categories(id)` with `ON DELETE RESTRICT`; `NOT NULL` on every column; `CHECK (base_price > 0)`; indexes supporting the browse queries (at least `category_id`, `status`).
- [x] New products default to `status = NOT_ACTIVE` and `stockStatus = OUT_OF_STOCK` (per EP-CAT-01), defined once in the entity or the service factory method, not scattered.
- [x] `ProductRepository` with the lookups needed by later tasks (`existsByCategoryId`/`countByCategoryId`, `findAllById`-style batch lookup).
- [x] Repository test (real PostgreSQL): persistence with defaults, FK violation when `categoryId` does not exist, `CHECK` violation for `basePrice <= 0`, and `ON DELETE RESTRICT` blocks deleting a category that still has products.

## Technical Notes / Constraints
- Reference the category by its ID (`categoryId`, as in the spec) rather than a `@ManyToOne Category` unless a later query truly needs the association. Think about what each choice costs: the ID-only form avoids lazy-loading and N+1 questions entirely, and the response DTOs only expose `categoryId`.
- Money: use `BigDecimal` mapped to `NUMERIC(p, 2)`. Pick the precision and document it. No currency column is in the spec (the Order Service owns `currency`), so do not add one.
- `description` can be long; check that the column type does not silently truncate.
- `stockStatus` is updated by a consumer concurrently with manager edits to the same row. Consider whether an `@Version` (optimistic locking) column is worth adding; if added, it must be an internal field not exposed in DTOs. Record the decision.
- Hard deletes are forbidden: do not add a delete method to the product service layer, and do not expose `DELETE` for products.
- Hibernate `ddl-auto` stays `validate`.
- **Money (decision):** `NUMERIC(12, 2)`, so up to 9 999 999 999.99. The entity normalizes `basePrice` to scale 2 and throws instead of rounding.
- **Text columns (decision):** `title VARCHAR(200)`, `description VARCHAR(5000)`. The same limits are enforced by Bean Validation on the DTO, so the column never truncates silently (a repository test stores a 5000-character description).
- **Concurrent `stockStatus` updates (decision): no `@Version`; `@DynamicUpdate` instead.** The real risk is a manager edit writing back a stale `stockStatus`. With `@DynamicUpdate`, Hibernate's `UPDATE` contains only the changed columns, so editing descriptive fields never touches `stock_status` (an integration test commits a concurrent `stock_status` change mid-transaction and proves it survives). `@Version` was rejected for now: the API has no `If-Match`/version field, so it would only protect a few milliseconds of read-modify-write, and it would make manager edits fail whenever an Inventory event happens to land at the same moment. Revisit it if concurrent manager edits become a requirement.
- Enum columns also have `CHECK` constraints listing the allowed values, as a database-level guard.
