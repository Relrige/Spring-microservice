---
status: TODO
service: inventory-service
---
# StockItem Entity, Repository and Migration

## Context
`StockItem` is the warehouse view of a product: quantities and packaging data, keyed by the Catalog `productId`. It never stores titles or prices.
*References:* [Inventory Spec — Service Overview](../../design/services-specs/inventory-service-spec.md), [Domain Entities — Inventory](../../design/entities/entities.md), [Inventory Worker Technical Insights §1–2](../../design/user-stories/inventory-worker/inventory-worker-technical-insights.md)

## Acceptance Criteria
- [ ] Create `StockItem` entity: `productId` (UUID, primary key, **assigned**, not generated), `availableQuantity`, `reservedQuantity`, and nullable `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`.
- [ ] Flyway migration `V1__create_stock_items_table.sql`: primary key on `product_id`; `NOT NULL` with default `0` for both quantities; `CHECK (available_quantity >= 0)`, `CHECK (reserved_quantity >= 0)`; `CHECK` that each packaging column is either null or `> 0`.
- [ ] A derived `packagingConfigured` property (all four values non-null and `> 0`) lives in one place (entity method), used by the stock listing, readiness and packaging endpoints. `totalQuantity = availableQuantity + reservedQuantity` is derived the same way and is not stored.
- [ ] `StockItemRepository` with the lookups needed by later tasks, including a lookup that takes a **pessimistic write lock** (`SELECT … FOR UPDATE`) by `productId` and a bulk variant for several IDs ordered by `productId`.
- [ ] Repository test (real PostgreSQL via Testcontainers): persistence with defaults, negative quantity violates the `CHECK`, zero/negative packaging violates the `CHECK`, `packagingConfigured` is correct for none/partial/all dimensions.

## Technical Notes / Constraints
- `reservedQuantity` is stored and kept in sync by the service (the entity doc also allows a computed column). Storing it keeps reads cheap and lets the `CHECK` protect it; the price is that every reservation change must update both numbers in one transaction. Record this decision.
- **Concurrency decision to make and record now**, because every stock-changing endpoint depends on it: pessimistic row lock (`FOR UPDATE`), optimistic `@Version` with retry, or a conditional `UPDATE … WHERE available_quantity >= :q`. The database `CHECK` constraints are the last line of defence in all cases. When several rows are locked in one transaction (reserve/release), lock them in a fixed order (sorted by `productId`) to avoid deadlocks.
- Use `Integer` quantities; decide an upper bound that avoids overflow on `availableQuantity + delta`.
- The ID is assigned by the caller (Catalog generates `productId`), so use a plain `@Id` without `@GeneratedValue`, and think about what `save()` does for an assigned ID (select-then-insert); a `Persistable`/explicit insert is an option where idempotent creation matters ([task 15](15-consume-product-created.md)).
- Hibernate `ddl-auto` stays `validate`; Flyway owns the schema.
