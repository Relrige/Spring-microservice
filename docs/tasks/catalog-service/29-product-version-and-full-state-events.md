---
status: TODO
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
- [ ] Flyway `V4__add_product_version.sql`: `products.version bigint NOT NULL DEFAULT 0`. `Product.version` is mapped with `@Version`, and `Product` is annotated `@DynamicUpdate`.
- [ ] `ProductRepository.updateStockStatus` (bulk JPQL) stays as it is and does **not** change `version`.
- [ ] Overlapping saves: an `ObjectOptimisticLockingFailureException` from EP-CAT-02 or EP-CAT-03 → `409` with type `conflict` and `detail` "The product was changed by someone else. Reload it and try again." (`GlobalExceptionHandler`). Nothing is changed and no event is stored.
- [ ] One payload record for both product events: `{ eventId, productId, title, categoryId, basePrice, status, version, timestamp }`, built from the product. `ProductCreatedEvent` is extended or replaced by it; the `eventType` header tells the types apart.
- [ ] EP-CAT-01 stores `ProductCreated` with the full state and `version: 0`.
- [ ] EP-CAT-02 stores `ProductUpdated` after every successful update, in the same transaction. EP-CAT-03 stores it only when the status actually changed (not for deactivating an inactive product or re-activating an active one, and not on `422`/`503`).
- [ ] The event's `version` is the value **after** the update: the product is flushed before the event is built.
- [ ] EP-CAT-09 answer adds `version`: `[ { productId, title, basePrice, status, stockStatus, version } ]`.
- [ ] OpenAPI: `internal.json` is regenerated for EP-CAT-09. `public.json` is regenerated for the documented `409` on EP-CAT-02/03. Both are committed.
- [ ] Tests:
  - Testcontainers Kafka: create → `ProductCreated` with title, status `NOT_ACTIVE` and version `0`;
  - update → `ProductUpdated` with the new title and version `1`;
  - activate (readiness stubbed with WireMock) → `ProductUpdated` with status `ACTIVE` and a higher version;
  - deactivating an already inactive product → no new event;
  - the records carry the headers `eventType` and `eventId`, and the payload's `eventId` equals the header;
  - `version` is unchanged by a stock event (`StockEventsIntegrationTest` or new);
  - a stock event between loading and saving a product doesn't lose the new `stockStatus` (`@DynamicUpdate`);
  - two overlapping updates of one product → one succeeds and the other answers `409` (e.g. two transactions coordinated in a test, or a stale entity saved directly against the repository);
  - batch-get JSON contains `version`.
- [ ] Order Service's and Inventory's batch-get clients ignore unknown fields (tolerant reader); confirm, as task 24 did for `stockStatus`.

## Technical Notes / Constraints
- **Settled (D5):** the version comes from JPA `@Version`, not from timestamps. It increases only when Hibernate updates the product row, so gaps don't matter: consumers only compare "greater than".
- **Settled (D6):** full state, with no `description` and no `stockStatus`. A narrow `ProductRenamed` event was rejected. `ProductCreated` stays the event type for creation; only its payload grows, which is additive.
- Outbox: use the existing `Outbox.append(...)` with aggregate type `Product`, the topic `KafkaTopics.PRODUCT_EVENTS` and key `productId`. Don't add ordering guarantees to the relay: consumers handle order with `version`.
- Hibernate increases the version at flush time. Read `product.getVersion()` only after `productRepository.saveAndFlush(...)` or an explicit flush, otherwise the event carries the old value.
- An EP-CAT-02 call that changes no value issues no `UPDATE`, so the version stays the same. The event repeats it, and consumers ignore it. That's intended; don't suppress the event.
- There is no `If-Match` / ETag; detecting a stale form in the browser is out of scope.
