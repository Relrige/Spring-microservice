---
status: DONE
service: catalog-service
---
# Product Version and Full-State Product Events (`ProductUpdated`)

## Context
Inventory will keep a copy of each product's title and status, so workers can search and sort the stock list by title (Inventory decision D8). For that, Catalog must announce every change of that data, and consumers must be able to tell a newer state from an older one: Catalog's outbox relay doesn't guarantee order, and Inventory's retry topics reorder events further.

This task:
- adds a version to `Product` (D5);
- makes both product events carry the full state, adding `ProductUpdated` (D6);
- adds the version to the internal batch-get (D7).

`@DynamicUpdate` also fixes an existing lost update: today a manager's save writes every column, so it can write back an old `stockStatus` that a stock event changed in the meantime.

Order across services: do this before Inventory tasks [22](../inventory-service/22-product-data-copy-from-events.md) and [23](../inventory-service/23-product-data-backfill.md). They tolerate a Catalog without this task (rows keep a `null` title), but they only have an effect once it is done. Independent of tasks 27 and 28.

*References:* [Catalog spec — Product version, Product Events, EP-CAT-01/02/03/09, revision 2026-10-22 (D5–D7)](../../design/services-specs/catalog-service-spec.md), [Events Catalogue — `ProductCreated`, `ProductUpdated`](../../design/services-requirements/events-catalogue.md#1-catalog--inventory-synchronization), [API Contracts — batch-get](../../design/services-requirements/api-contracts.md#3-catalog-service), [Entities — Product](../../design/entities/entities.md)

## Acceptance Criteria
- [x] Flyway `V4__add_product_version.sql`: `products.version bigint NOT NULL DEFAULT 0`. `Product.version` is mapped with `@Version`, and `Product` is annotated `@DynamicUpdate`.
- [x] `ProductRepository.updateStockStatus` (bulk JPQL) stays as it is and does **not** change `version`.
- [x] Overlapping saves: an `ObjectOptimisticLockingFailureException` from EP-CAT-02 or EP-CAT-03 → `409` with type `conflict` and `detail` "The product was changed by someone else. Reload it and try again." (`GlobalExceptionHandler`). Nothing is changed and no event is stored.
- [x] One payload record for both product events: `{ eventId, productId, title, categoryId, basePrice, status, version, timestamp }`, built from the product. `ProductCreatedEvent` is extended or replaced by it; the `eventType` header tells the types apart.
- [x] EP-CAT-01 stores `ProductCreated` with the full state and `version: 0`.
- [x] EP-CAT-02 stores `ProductUpdated` after every successful update, in the same transaction. EP-CAT-03 stores it only when the status actually changed (not for deactivating an inactive product or re-activating an active one, and not on `422`/`503`).
- [x] The event's `version` is the value **after** the update: the product is flushed before the event is built.
- [x] EP-CAT-09 answer adds `version`: `[ { productId, title, basePrice, status, stockStatus, version } ]`.
- [x] OpenAPI: `internal.json` is regenerated for EP-CAT-09. `public.json` is regenerated for the documented `409` on EP-CAT-02/03. Both are committed.
- [x] Tests:
  - Testcontainers Kafka: create → `ProductCreated` with title, status `NOT_ACTIVE` and version `0`;
  - update → `ProductUpdated` with the new title and version `1`;
  - activate (readiness stubbed with WireMock) → `ProductUpdated` with status `ACTIVE` and a higher version;
  - deactivating an already inactive product → no new event;
  - the records carry the headers `eventType` and `eventId`, and the payload's `eventId` equals the header;
  - `version` is unchanged by a stock event (`StockEventsIntegrationTest` or new);
  - a stock event between loading and saving a product doesn't lose the new `stockStatus` (`@DynamicUpdate`);
  - two overlapping updates of one product → one succeeds and the other answers `409` (e.g. two transactions coordinated in a test, or a stale entity saved directly against the repository);
  - batch-get JSON contains `version`.
- [x] Order Service's and Inventory's batch-get clients ignore unknown fields (tolerant reader); confirm, as task 24 did for `stockStatus`.

## Technical Notes / Constraints
- **Settled (D5):** the version comes from JPA `@Version`, not from timestamps. It increases only when Hibernate updates the product row, so gaps don't matter: consumers only compare "greater than".
- **Settled (D6):** full state, with no `description` and no `stockStatus`. A narrow `ProductRenamed` event was rejected. `ProductCreated` stays the event type for creation; only its payload grows, which is additive.
- Outbox: use the existing `Outbox.append(...)` with aggregate type `Product`, the topic `KafkaTopics.PRODUCT_EVENTS` and key `productId`. Don't add ordering guarantees to the relay: consumers handle order with `version`.
- Hibernate increases the version at flush time. Read `product.getVersion()` only after `productRepository.saveAndFlush(...)` or an explicit flush, otherwise the event carries the old value.
- An EP-CAT-02 call that changes no value issues no `UPDATE`, so the version stays the same. The event repeats it, and consumers ignore it. That's intended; don't suppress the event.
- There is no `If-Match` / ETag; detecting a stale form in the browser is out of scope.

## Implementation Notes (2026-10-22)
- **Version.** Flyway `V4__add_product_version.sql` adds `products.version BIGINT NOT NULL DEFAULT 0`. `Product.version` is a primitive `long` with `@Version`; `@DynamicUpdate` was already on `Product` (task 14), so only its comment changed. `ProductRepository.updateStockStatus` is untouched: a JPQL bulk update without `versioned` does not change `version`.
- **409.** `GlobalExceptionHandler` maps `ObjectOptimisticLockingFailureException` to `409` with type `conflict` and the detail from the task, and logs it at `INFO`. PUT and PATCH document the `409`.
- **One event record.** `ProductCreatedEvent` was renamed to `events/ProductEvent` (`git mv`, so the history stays): `{ eventId, productId, title, categoryId, basePrice, status, version, timestamp }`, constants `CREATED = "ProductCreated"` and `UPDATED = "ProductUpdated"`, and a factory `ProductEvent.of(Product)`. `ProductService.appendEvent(type, product)` builds it and appends it with aggregate type `Product`, topic `KafkaTopics.PRODUCT_EVENTS` and key `productId`, as before.
- **When events are stored.**
  - EP-CAT-01: after `saveAndFlush`, so the event carries `version: 0`.
  - EP-CAT-02: after `saveAndFlush`, in the same transaction, after every successful update; a conflict fails at the flush, before the append.
  - EP-CAT-03: in the existing short write transaction after the readiness call. If the product already has the requested status, the transaction returns without a flush or event; otherwise the status changes, `productRepository.flush()` runs the `UPDATE` (and the version check), then the event is appended. `422`/`503` throw before that transaction starts.
- **EP-CAT-09.** `BatchProductResponse` adds `version` (required in `internal.json`).
- **Tolerant readers confirmed.**
  - Order: `CatalogClientAdapterTest` already deserializes a batch-get answer with an `unknownField`.
  - Inventory: `CatalogProduct` is `@JsonIgnoreProperties(ignoreUnknown = true)`, and its `ProductCreatedEvent` ignores unknown payload fields (message converter, see its Javadoc). Its `productCreatedOnly` record filter skips `ProductUpdated` until Inventory task 22.
- **Verified:**
  - `ProductEventTest`: `of` copies the state, required fields, JSON shape (no `description`, no `stockStatus`, ISO timestamp), round trip.
  - `ProductServiceTest`:
    - full `ProductCreated` with `version` 0;
    - `ProductUpdated` with the new title and price and the product's current version;
    - a conflict at `saveAndFlush` stores no event;
    - activation and deactivation flush and append `ProductUpdated`;
    - the same status twice stores nothing and doesn't flush;
    - `422`/`503` store nothing.
  - `ProductControllerTest`: `409` body for PUT and PATCH; batch-get JSON with `version`.
  - `ProductEventsIntegrationTest` (Testcontainers Kafka and PostgreSQL, WireMock for readiness):
    - create → `ProductCreated` with title, category, price, `NOT_ACTIVE`, version 0;
    - update → `ProductUpdated` with the new title and version 1;
    - activate → `ProductUpdated` `ACTIVE` with a higher version;
    - deactivating an inactive product → no new record and no `ProductUpdated` outbox row;
    - `eventType` and `eventId` headers, and the payload's `eventId` equals the header for every record;
    - the earlier partitioning and failed-create tests still pass.
  - `ProductIntegrationTest`:
    - a stock update committed between load and save keeps `IN_STOCK` and gives version 1, not a conflict;
    - two overlapping saves: a transaction loads version 0, another connection commits version 1, and `ProductService.updateProduct` then fails with `ObjectOptimisticLockingFailureException`, leaving the other save's title, version 1 and no outbox row;
    - a stale detached entity saved directly is rejected.
  - `StockEventsIntegrationTest`: version stays 0 after two stock events.
  - `CatalogReadAndStatusIntegrationTest`: batch-get `version` (0 and 5).
  - `KafkaSetupIntegrationTest` with the new record; `ProductRepositoryTest`; `OpenApiDocumentTest` after regenerating both documents.

## Deviations from spec / task
- **No end-to-end `409` through HTTP.** The test of two overlapping saves drives `ProductService` inside a test transaction and asserts the exception plus "nothing changed, no event"; the `409` mapping is covered by `ProductControllerTest`. Making two real HTTP requests overlap needs a hook inside the service between load and flush, which only tests would use. The task allowed either form.
- **EP-CAT-03 skips the write when nothing changes.** Before, it always assigned the status, which with `@DynamicUpdate` already issued no `UPDATE`. Now it returns early, which makes "no event when the status didn't change" explicit and avoids a pointless flush. Behaviour for the caller is unchanged (`200`).
- **`ProductCreatedEvent` was renamed rather than extended**, because one record now serves two event types; `ProductCreatedEvent` would be a misleading name for `ProductUpdated`. The wire format of `ProductCreated` only grows (additive).
- **Catalog's own test "deserialization ignores unknown properties"** was dropped with the old record: Catalog doesn't consume product events, and the consumer-side tolerance is Inventory's to test (task 22).
