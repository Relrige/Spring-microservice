---
status: TODO
service: inventory-service
---
# EP-INV-EVT-01/03: Copy of Product Data from `ProductCreated` and `ProductUpdated`

## Context
Workers need to search and sort the stock list by product title (frontend backlog BL-02). Joining titles in the browser only works within one page, so Inventory keeps its own copy of Catalog's `title`, `status` and `version` in `stock_items` (D8). Catalog's product events carry the full product state from Catalog [task 29](../catalog-service/29-product-version-and-full-state-events.md) on.

This task:
- adds the columns and the one upsert that writes them ("newer version wins");
- extends the `ProductCreated` handling;
- adds the `ProductUpdated` handling;
- makes the existence-check fallback of EP-INV-02/03 store the copy from the batch-get answer.

Order across services: works with or without Catalog task 29, because old events simply carry no copy. The copy only fills in once task 29 is deployed. Followed by [task 23](23-product-data-backfill.md) (backfill) and [task 24](24-stock-list-title-search-and-sort.md) (stock list).

*References:* [Inventory spec — Copy of product data, Catalog existence check, EP-INV-02, EP-INV-EVT-01, EP-INV-EVT-03, revision 2026-10-22 (D8)](../../design/services-specs/inventory-service-spec.md), [Events Catalogue — `ProductCreated`, `ProductUpdated`](../../design/services-requirements/events-catalogue.md#1-catalog--inventory-synchronization), [Entities — StockItem](../../design/entities/entities.md), [Catalog spec — Product version, Product Events](../../design/services-specs/catalog-service-spec.md)

## Acceptance Criteria
- [ ] Flyway `V5__add_product_data_copy.sql`: nullable `stock_items.title varchar(255)`, `product_status varchar(20)` (check: `NULL` or `ACTIVE` / `NOT_ACTIVE`), and `product_version bigint`.
- [ ] `StockItemEntity` maps the three fields read-only (`insertable = false, updatable = false`). Inventory gets its own enum `ProductStatus { ACTIVE, NOT_ACTIVE }`.
- [ ] One repository method runs the upsert of *Copy of product data* (native `INSERT … ON CONFLICT (product_id) DO UPDATE … WHERE product_version IS NULL OR product_version < EXCLUDED.product_version`). Everything that writes the copy calls this method.
- [ ] `ProductCreated` (EP-INV-EVT-01):
  - the payload record reads optional `title`, `status` and `version`;
  - in the same transaction as the `processed_events` key, a missing row is created and the copy is stored with the upsert;
  - without `version`, or with an unknown `status`, the row is only created (`ON CONFLICT DO NOTHING`, as today), and an unknown `status` logs a `WARN`.
- [ ] `ProductUpdated` (EP-INV-EVT-03):
  - handled by the **same listener container** as `ProductCreated`, dispatching by the `eventType` header, with the existing retry topics and DLT;
  - `eventId`, `productId`, `title`, `status` and `version` are mandatory (missing → DLT);
  - applied with the upsert, with no `processed_events` key;
  - an unknown `status` creates a missing row, writes no copy, and logs a `WARN`.
- [ ] Existence-check fallback (EP-INV-02/03): `ProductCatalog` returns the product's `title`, `status` and `version` instead of a boolean; `CatalogProduct` adds `version`. When a missing stock row is created, the copy from the answer is stored with the upsert (only creation if the answer has no version or an unknown status). Existing rows trigger no Catalog call, as today.
- [ ] Tests (Testcontainers PostgreSQL and Kafka):
  - `ProductCreated` with full state → a row with title, status `NOT_ACTIVE` and version `0`;
  - `ProductCreated` without the new fields → a row without a copy;
  - `ProductUpdated` v2 then v1 → the title of v2 stays;
  - the same `ProductUpdated` twice → one change;
  - `ProductUpdated` for an unknown product → the row is created with zero quantities and the copy;
  - `ProductUpdated` after `ProductCreated` → updated;
  - `ProductCreated` (v0) arriving after `ProductUpdated` (v1) → no change;
  - a `ProductUpdated` without `version` → DLT;
  - an unknown status → row without a copy, no DLT;
  - quantities and packaging are never changed by either event;
  - a stock adjustment after a copy update keeps the copy (read-only mapping);
  - fallback (WireMock Catalog): adjusting a product without a row stores the copy from the batch-get answer.
- [ ] The Inventory OpenAPI documents are unchanged by this task (no REST change); `OpenApiDocumentTest` still passes.

## Technical Notes / Constraints
- **Settled (D8):** the copy lives in `stock_items`, not in a separate table, and is written only through the version-guarded upsert. Inventory never edits it on its own.
- **One listener container for both event types.** Two `@KafkaListener` containers in the same consumer group on one topic would split the partitions between them, and each would drop the other's type, losing events. Rename or generalize `ProductCreatedListener` (for example `ProductEventsListener`) and replace the `productCreatedOnly` filter with one that accepts both types.
- Both payloads have the same shape. One record class is enough, with the type taken from the header.
- The upsert and the `processed_events` insert of `ProductCreated` run in one transaction. `ProductUpdated` needs no key, because the version check makes it idempotent.
- The version check and the write are one SQL statement. Don't read the row first and decide in Java: concurrent consumers and replicas would race.
- `product_status` stores the enum name. The batch-get answer keeps `status` as text (tolerant reader) and maps it to the enum only when writing the copy.
