---
status: TODO
service: inventory-service
---
# EP-INV-JOB-01: Fill Missing Product Data

## Context
After [task 22](22-product-data-copy-from-events.md), new and updated products get their copied title, status and version from events. Rows that existed before have no copy, and neither do rows created from an event published before Catalog [task 29](../catalog-service/29-product-version-and-full-state-events.md). They would keep a `null` title until the product is next edited. A scheduled job fills them from Catalog's internal batch-get, which returns `version` from task 29 on (D9).

Builds on task 22 (columns, upsert, `ProductCatalog` returning product data) and needs Catalog task 29 deployed to have an effect.

*References:* [Inventory spec — EP-INV-JOB-01, Copy of product data, Catalog existence check, Configuration, revision 2026-10-22 (D9)](../../design/services-specs/inventory-service-spec.md), [Catalog spec — EP-CAT-09](../../design/services-specs/catalog-service-spec.md), [API Contracts — batch-get](../../design/services-requirements/api-contracts.md#3-catalog-service)

## Acceptance Criteria
- [ ] A scheduled job runs with a fixed delay of `PRODUCT_DATA_BACKFILL_INTERVAL` (default `60s`, property `inventory.product-data-backfill.interval`).
- [ ] Each run:
  - selects up to 100 product IDs with `title IS NULL`, ordered by `product_id`;
  - calls batch-get once per batch;
  - applies the upsert of task 22 for every product in the answer that has a `version` and a known `status`, each in its own short transaction;
  - continues after the last processed ID, at most 10 batches per run.
- [ ] Catalog unavailable (the client's `CatalogUnavailableException` after its one retry) → one `WARN`, the run ends, nothing changes, and the next run tries again.
- [ ] Products absent from the answer keep `null`. The run logs their count at `WARN`.
- [ ] An answer without `version` → the row is skipped.
- [ ] No row with a title is ever requested from Catalog. When no row lacks a title, the run makes no HTTP call.
- [ ] The interval is added to the Compose and Kubernetes configuration only if they list Inventory's optional settings explicitly; otherwise the default applies.
- [ ] Tests (Testcontainers PostgreSQL, WireMock Catalog):
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
