---
status: DONE
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
- [x] Flyway `V5__add_product_data_copy.sql`: nullable `stock_items.title varchar(255)`, `product_status varchar(20)` (check: `NULL` or `ACTIVE` / `NOT_ACTIVE`), and `product_version bigint`.
- [x] `StockItemEntity` maps the three fields read-only (`insertable = false, updatable = false`). Inventory gets its own enum `ProductStatus { ACTIVE, NOT_ACTIVE }`.
- [x] One repository method runs the upsert of *Copy of product data* (native `INSERT … ON CONFLICT (product_id) DO UPDATE … WHERE product_version IS NULL OR product_version < EXCLUDED.product_version`). Everything that writes the copy calls this method.
- [x] `ProductCreated` (EP-INV-EVT-01):
  - the payload record reads optional `title`, `status` and `version`;
  - in the same transaction as the `processed_events` key, a missing row is created and the copy is stored with the upsert;
  - without `version`, or with an unknown `status`, the row is only created (`ON CONFLICT DO NOTHING`, as today), and an unknown `status` logs a `WARN`.
- [x] `ProductUpdated` (EP-INV-EVT-03):
  - handled by the **same listener container** as `ProductCreated`, dispatching by the `eventType` header, with the existing retry topics and DLT;
  - `eventId`, `productId`, `title`, `status` and `version` are mandatory (missing → DLT);
  - applied with the upsert, with no `processed_events` key;
  - an unknown `status` creates a missing row, writes no copy, and logs a `WARN`.
- [x] Existence-check fallback (EP-INV-02/03): `ProductCatalog` returns the product's `title`, `status` and `version` instead of a boolean; `CatalogProduct` adds `version`. When a missing stock row is created, the copy from the answer is stored with the upsert (only creation if the answer has no version or an unknown status). Existing rows trigger no Catalog call, as today.
- [x] Tests (Testcontainers PostgreSQL and Kafka):
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
- [x] The Inventory OpenAPI documents are unchanged by this task (no REST change); `OpenApiDocumentTest` still passes.

## Technical Notes / Constraints
- **Settled (D8):** the copy lives in `stock_items`, not in a separate table, and is written only through the version-guarded upsert. Inventory never edits it on its own.
- **One listener container for both event types.** Two `@KafkaListener` containers in the same consumer group on one topic would split the partitions between them, and each would drop the other's type, losing events. Rename or generalize `ProductCreatedListener` (for example `ProductEventsListener`) and replace the `productCreatedOnly` filter with one that accepts both types.
- Both payloads have the same shape. One record class is enough, with the type taken from the header.
- The upsert and the `processed_events` insert of `ProductCreated` run in one transaction. `ProductUpdated` needs no key, because the version check makes it idempotent.
- The version check and the write are one SQL statement. Don't read the row first and decide in Java: concurrent consumers and replicas would race.
- `product_status` stores the enum name. The batch-get answer keeps `status` as text (tolerant reader) and maps it to the enum only when writing the copy.

## Decisions (2026-10-22)
Settled before implementation (autopilot run for Inventory tasks 22–24; the user accepted every recommended answer):
- The run works on its own branch `inventory-service/product-data-copy`, cut from the branch of the Catalog run (PR #22) because that branch holds the design commit and these task files. The PR targets that branch, so its diff shows only Inventory.
- A `ProductUpdated` without `title`, `status` or `version` goes to the DLT **without retries**, like an unconvertible payload: retrying cannot add a missing field.
- A `ProductCreated` that has a `version` but no `title` is handled like one without a version: only the row is created, the copy is not written, and a `WARN` is logged.
- An invalid `productStatus` on EP-INV-01 (task 24) gets its `errors.productStatus` from a general type-mismatch handler, which also gives an invalid `packagingConfigured` its `errors.packagingConfigured`.
- After the spec sync, the frontend backlog item BL-02 gets a progress note in its own commit.

## Implementation Notes (2026-10-22)
- **Schema.** Flyway `V5__add_product_data_copy.sql` adds the nullable `title varchar(255)`, `product_status varchar(20)` (check `ACTIVE`/`NOT_ACTIVE`) and `product_version bigint`. `StockItemEntity` maps them read-only (`insertable = false, updatable = false`), and the new enum `entities/ProductStatus` has a tolerant `parse(String)` that returns empty for `null` or an unknown value.
- **The one upsert.** `StockItemRepository.upsertProductData(id, title, status, version)` is the native statement of the spec (`INSERT … ON CONFLICT (product_id) DO UPDATE … WHERE product_version IS NULL OR product_version < EXCLUDED.product_version`). It returns 1 when the row was created or its copy updated, 0 when the stored copy is as new or newer.
- **`StockService.storeProductData`** (private) decides per source: a `version`, a `title` and a known status → the upsert; otherwise `insertIfAbsent` (create only), with a `WARN` when a versioned source has no title or an unknown status. The event handlers, the existence-check fallback and (task 23) the backfill all go through it, so the upsert has one caller.
- **Events.** `ProductCreatedEvent` became `events/ProductEvent`, which reads the optional `title`, `status` (as text) and `version`. `eventId` and `productId` stay mandatory in the constructor. `ProductCreatedListener` became `ProductEventsListener`: one `@KafkaListener` container on `catalog.product-events` with the new filter `productEventsOnly` (both types, from `KafkaConfig.onlyEventTypes`). It dispatches on the `eventType` header and keeps the existing `@RetryableTopic` retry topics and DLT. The properties moved from `inventory.kafka.product-created.*` to `inventory.kafka.product-events.*`.
  - `ProductCreated`: `StockService.applyProductCreated(event)` records the `processed_events` key and calls `storeProductData` in one transaction. The result enum is now `APPLIED` (row created or copy updated) / `UNCHANGED` / `DUPLICATE_EVENT`.
  - `ProductUpdated`: the listener checks `title`, `status` and `version` and throws `InvalidProductEventException` if one is missing; `@RetryableTopic(exclude = …)` sends it to the DLT at once. `StockService.applyProductUpdated(event)` calls `storeProductData` with no idempotency key.
- **Existence-check fallback.** `ProductCatalog` now offers `findAll(ids)`, plus a default `find(id)` built on it, returning `services/CatalogProductData { productId, title, status, version }`. The status stays text and the version nullable. `CatalogProduct` adds `version`. `requireKnownProduct` returns Catalog's answer (or `null` when the row exists and Catalog was not called), and `getOrCreateForUpdate` writes it through `storeProductData` inside the locking transaction.
- **Verified:**
  - `StockItemRepositoryTest`:
    - the upsert creates a row with zero quantities and the copy;
    - newer wins, while older and equal versions return 0 and change nothing;
    - an existing row without a copy gets one and keeps its quantities and packaging;
    - the check constraint rejects an unknown status;
    - saving a loaded entity after a newer copy was written does not restore the old copy (read-only mapping).
  - `StockServiceTest`: `applyProductCreated` with full state, an older state, no version, an unknown status (`ARCHIVED`, lower-case `active`), a version without a title, and a duplicate; `applyProductUpdated` with and without a known status (no `processed_events` call); the fallback with a versioned answer (upsert) and with an unknown status (create only). `StockServicePackagingTest` adapted to `find`.
  - `CatalogProductCatalogTest`: `find` returns title, status and version and ignores unknown fields; an answer without `version` and with an unknown status is kept as received; `findAll` sends all IDs in one request and returns the known products; the earlier retry and error cases still pass.
  - `EventTypeFilterTest`: `productEventsOnly` keeps both types and drops others and records without a type.
  - `ConsumedEventsIntegrationTest` (Testcontainers Kafka and PostgreSQL):
    - `ProductCreated` with full state → title, `NOT_ACTIVE`, version 0;
    - `ProductCreated` without the new fields → a row without a copy;
    - `ProductUpdated` after `ProductCreated` → updated;
    - v2, then v1, v2 again and `ProductCreated` v0 → v2 stays;
    - `ProductUpdated` for an unknown product → row with zero quantities and the copy;
    - `ProductUpdated` without `version` → DLT, not the retry topic;
    - unknown status for both types → rows without a copy, no DLT;
    - quantities, reservations and packaging untouched by both events;
    - a stock adjustment and a packaging change after a copy update keep the copy;
    - other types (`ProductDeleted`, no header) are still skipped.
  - `CatalogExistenceCheckIntegrationTest` (WireMock Catalog): adjusting a product without a row stores the copy from the batch-get answer; an answer without `version` creates the row without a copy.
  - `FailAfterCommitExperimentTest`, `MissingSourceTopicsStartupTest`, and `OpenApiDocumentTest` unchanged and green (no REST change).

## Deviations from spec / task
- **One private writer instead of only one repository method.** The task asks that everything writing the copy call the one repository method. They do, but through `StockService.storeProductData`, which also decides between the upsert and create-only and logs the `WARN`. Repeating that rule in the listener, the fallback and the backfill would let the three drift apart.
- **`ProductCreatedResult` changed its values.** `CREATED`/`ALREADY_EXISTED` can no longer be told apart: the upsert answers 1 both for "created" and for "copy updated". Telling them apart would need `RETURNING (xmax = 0)`, a PostgreSQL internal. Now `APPLIED`/`UNCHANGED`, used only for the log line.
- **`ProductCatalog.find` is a default method over `findAll`.** The task names only the existence check's return type. Task 23 needs a batch call anyway, and one HTTP code path (with its retry and error mapping) serves both.
- **Versioned source without a title** is treated like one without a version (Decisions above). The spec only mentions a missing version or an unknown status. Catalog's `title` is `NOT NULL`, so this protects against a broken producer rather than a normal case.
- **The listener property prefix was renamed** (`product-created` → `product-events`). It is set only in `application.yml`; no Compose or Kubernetes file sets it.

*Spec sync (2026-10-22):* now part of the Inventory spec (*Copy of product data*: no title, no copy; EP-INV-EVT-03: missing fields → DLT without retries; revision 2026-10-22, implementation note) and the Events Catalogue (`ProductUpdated`, Inventory consumer).
