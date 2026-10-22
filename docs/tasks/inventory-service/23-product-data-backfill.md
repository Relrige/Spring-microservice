---
status: DONE
service: inventory-service
---
# EP-INV-JOB-01: Fill Missing Product Data

## Context
After [task 22](22-product-data-copy-from-events.md), new and updated products get their copied title, status and version from events. Rows that existed before have no copy, and neither do rows created from an event published before Catalog [task 29](../catalog-service/29-product-version-and-full-state-events.md). They would keep a `null` title until the product is next edited. A scheduled job fills them from Catalog's internal batch-get, which returns `version` from task 29 on (D9).

Builds on task 22 (columns, upsert, `ProductCatalog` returning product data) and needs Catalog task 29 deployed to have an effect.

*References:* [Inventory spec — EP-INV-JOB-01, Copy of product data, Catalog existence check, Configuration, revision 2026-10-22 (D9)](../../design/services-specs/inventory-service-spec.md), [Catalog spec — EP-CAT-09](../../design/services-specs/catalog-service-spec.md), [API Contracts — batch-get](../../design/services-requirements/api-contracts.md#3-catalog-service)

## Acceptance Criteria
- [x] A scheduled job runs with a fixed delay of `PRODUCT_DATA_BACKFILL_INTERVAL` (default `60s`, property `inventory.product-data-backfill.interval`).
- [x] Each run:
  - selects up to 100 product IDs with `title IS NULL`, ordered by `product_id`;
  - calls batch-get once per batch;
  - applies the upsert of task 22 for every product in the answer that has a `version` and a known `status`, each in its own short transaction;
  - continues after the last processed ID, at most 10 batches per run.
- [x] Catalog unavailable (the client's `CatalogUnavailableException` after its one retry) → one `WARN`, the run ends, nothing changes, and the next run tries again.
- [x] Products absent from the answer keep `null`. The run logs their count at `WARN`.
- [x] An answer without `version` → the row is skipped.
- [x] No row with a title is ever requested from Catalog. When no row lacks a title, the run makes no HTTP call.
- [x] The interval is added to the Compose and Kubernetes configuration only if they list Inventory's optional settings explicitly; otherwise the default applies.
- [x] Tests (Testcontainers PostgreSQL, WireMock Catalog):
  - 150 rows without a title → two batch-get calls (100 + 50) and all rows filled;
  - a row whose copy has version 5 and an answer with version 3 → unchanged (a newer event won);
  - Catalog `503` → no change, and the job doesn't throw;
  - an unknown product → stays `null`, and the other rows are filled;
  - no rows without a title → no Catalog call;
  - an answer without `version` → skipped.
  - Call the job method directly in tests; don't wait for the schedule.

## Technical Notes / Constraints
- **Settled (D9):** a periodic job, not a one-off migration. A Flyway migration can't call Catalog, and the job also heals rows created later from old events.
- No distributed lock (no ShedLock). Running on several replicas at once is harmless, because the upsert is idempotent and keeps the newest version.
- Reuse the existing Catalog client with its timeouts and retry. The batch size of 100 is Catalog's batch-get limit.
- Inventory already enables scheduling (`SchedulingConfig`); follow how the outbox jobs are configured.
- Don't hold a database transaction open across the Catalog call: read the IDs, call Catalog, then write.

## Implementation Notes (2026-10-22)
- **Job.** `services/ProductDataBackfill.fillMissingProductData()` is scheduled with `fixedDelayString` and `initialDelayString` both set to `inventory.product-data-backfill.interval` (`${PRODUCT_DATA_BACKFILL_INTERVAL:60s}` in `application.yml`). It returns the number of rows filled, so tests can call it directly.
- **One run.**
  - `StockItemRepository.findIdsWithoutTitleAfter(after, Limit.of(100))` is a JPQL query: `title is null and productId > :after order by productId`. The first batch starts after the nil UUID, which sorts below every random UUID.
  - `ProductCatalog.findAll(ids)` (task 22) makes one batch-get call per batch, with the client's timeouts and one retry.
  - Each answer that has a `version` goes to `StockService.applyCatalogProductData`. That method runs in its own `@Transactional` and writes through the same `storeProductData` as the events, so an unknown status is logged and not copied there.
  - The next batch starts after the batch's last ID. The run stops after a short batch or after 10 batches.
  - The ID query and the Catalog call run outside any transaction.
- **Outcomes.** `CatalogUnavailableException` → one `WARN`, and the run ends. The count of products Catalog doesn't know is summed over the run and logged once at `WARN`. A run with no rows to fill makes no HTTP call and logs nothing.
- **Configuration.** `inventory-service/.env.example` lists `PRODUCT_DATA_BACKFILL_INTERVAL` commented out, next to the other optional Catalog settings. The Kubernetes ConfigMap sets no optional Inventory settings, so it is unchanged. The test `application.properties` sets the interval to `1d`, so no scheduled run interferes with a test.
- **Verified** (`ProductDataBackfillIntegrationTest`, Testcontainers PostgreSQL and Kafka, WireMock Catalog, job called directly):
  - 150 rows without a title → exactly two calls, whose stubs match the first 100 and the last 50 IDs in PostgreSQL order (so no row with a title is requested); all rows filled, quantities untouched, a row that already had a copy unchanged;
  - an event with version 5 applied while the Catalog call (version 3) is in flight → version 5 stays, and the run reports 0;
  - Catalog `503` → two attempts (the client's retry), nothing changed, the job returns normally;
  - an unknown product stays `null` while the other is filled;
  - no rows without a title → no Catalog call;
  - an answer without `version` → skipped;
  - 1100 unknown rows → exactly 10 calls with 1000 distinct IDs (the per-run cap, and the cursor moves past unknown products).
  - `InventoryServiceApplicationTests` (context starts with the new scheduled bean).

## Deviations from spec / task
- **The first run waits one interval.** The spec measures the interval "from the end of the previous run" and says nothing about startup. An immediate run at startup would call Catalog while it may still be starting, and in tests it would race with the WireMock stubs. The outbox cleanup job waits the same way.
- **A cursor instead of re-selecting from the start.** The task says "continues after the last processed ID", and that is implemented literally. Without it, a batch of products Catalog doesn't know would be selected again and again, and the run would never reach the rows after them; the 1100-row test covers this.
- **The test properties push the schedule to `1d`** instead of disabling scheduling. The other scheduled jobs (outbox relay) must keep running in the integration tests, so a global switch would not work.
