# Catalog Service — Implementation Spec

Owns the product catalog, category hierarchy, current base prices, and a coarse stock-availability read-model. It is the **master domain** for products: it generates `productId` (UUID) which all downstream services reference. It never stores stock quantities or packaging data — those belong to Inventory Service.

The stock-availability read-model (`Product.stockStatus`) is updated asynchronously via Inventory events; Catalog Service never queries Inventory on read paths.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `Category` (`id`, `name`); `Product` (`id`, `title`, `description`, `categoryId`, `basePrice`, `status`, `stockStatus`, `version`) |
| **Key enums** | `ProductStatus { ACTIVE, NOT_ACTIVE }`; `StockStatus { IN_STOCK, OUT_OF_STOCK }` |
| **Events emitted** | `ProductCreated`, `ProductUpdated` (see *Product Events*) |
| **Events consumed** | `ProductStockReplenished`, `ProductStockDepleted` (both from Inventory Service) |
| **API description** | OpenAPI documents `catalog-service/openapi/public.json` and `internal.json`, generated from the code ([OpenAPI Conventions](../services-requirements/openapi-conventions.md)); pilot service for them |

---

## Cross-cutting Conventions

### Product visibility

Who may see `NOT_ACTIVE` products is one business rule, applied by the read endpoints EP-CAT-04 and EP-CAT-05 (D1). It is not a security rule: both endpoints are open to everyone, and the role only changes what is returned.

| Caller (role from `X-User-Role`) | Sees |
|---|---|
| **Staff:** `CATALOG_MANAGER`, `INVENTORY_WORKER` | `ACTIVE` and `NOT_ACTIVE` products; may use the `status` filter of EP-CAT-04 |
| Anonymous, `CUSTOMER`, `ADMIN` | `ACTIVE` products only; `status` is ignored, and a `NOT_ACTIVE` product answers the same `404` as a missing one |

Staff need inactive products because the whole preparation of a product happens while it is inactive: the manager creates and edits it, the worker sets up stock and packaging, and only then is it activated. The rule lives in one place in the code (`ProductService.visibleStatus`). The API Gateway forwards the role on both paths (route mode `Optional`, D3).

### Product version

`Product.version` (`bigint`, JPA `@Version`, D5) starts at `0` and is increased by Hibernate on every update of the product row. It serves two purposes:
- **Ordering of product events.** Every product event carries the version after the change, so a consumer can tell a newer state from an older one even when events arrive out of order (see *Product Events*).
- **Optimistic locking.** Two overlapping saves of the same product (EP-CAT-02, EP-CAT-03) cannot silently overwrite each other: the second fails and answers `409 Conflict` ("The product was changed by someone else. Reload it and try again."). There is no `If-Match` / ETag: a client that loaded the product earlier and saves later is not detected; only overlapping transactions are.

The stock-status update from Inventory's events (EP-CAT-EVT-01/02) is a bulk JPQL update that does **not** increase the version: `stockStatus` is Inventory's data and not part of the product events. `Product` is mapped with `@DynamicUpdate`, so a save writes only the columns it changed and can never write back a stale `stockStatus` that a stock event changed in the meantime.

### Product Events

| | |
|---|---|
| **Topic** | `catalog.product-events` (owned and declared by Catalog), 3 partitions |
| **Key** | `productId` |
| **Headers** | `eventType` = `ProductCreated` or `ProductUpdated`; `eventId` (the same value as in the payload) |
| **Payload** (both types, D6) | `{ eventId, productId, title, categoryId, basePrice, status, version, timestamp }` — the full product state after the change, without `description` (long, and no consumer needs it) and without `stockStatus` (Inventory's data) |

- **When:** `ProductCreated` by EP-CAT-01 (`version: 0`); `ProductUpdated` after every successful EP-CAT-02, and after an EP-CAT-03 that changes the status. A status request that changes nothing (deactivating an inactive product, re-activating an active one) emits nothing.
- **Delivery:** transactional outbox. The event is stored in the same transaction as the product change and published by the relay afterwards; at-least-once.
- **Order is not guaranteed.** The relay publishes in `created_at` order but may run on several replicas, and consumers use non-blocking retry topics. Consumers therefore apply an event only if its `version` is greater than the one they stored, which also makes repeats harmless (Inventory: EP-INV-EVT-01/03).
- The version in the payload must be the value **after** the update: the service flushes the product before it builds the event (Hibernate increases the version at flush time). An EP-CAT-02 call that changes no value leaves the version unchanged; its event repeats the stored version and is ignored by consumers.

---

## EP-CAT-01: Create Product

**Type:** `HTTP POST /catalog/products`
**Caller:** Catalog Manager (via API Gateway)
**Auth:** `ROLE_CATALOG_MANAGER`

**Steps:**
1. Validate request body: `title` non-empty; `description` non-empty; `categoryId` valid UUID; `basePrice` > 0.
2. Verify that `categoryId` exists in the `categories` table. If not → `422`.
3. Persist `Product(id=UUID, title, description, categoryId, basePrice, status=NOT_ACTIVE, stockStatus=OUT_OF_STOCK, version=0)`.
4. In the same transaction, store the event `ProductCreated { eventId, productId, title, categoryId, basePrice, status, version, timestamp }` in the outbox table; a relay publishes it to the message broker (topic `catalog.product-events`, key `productId`) after the commit (see *Product Events*). Delivery is at-least-once (transactional outbox), so consumers deduplicate by `eventId` or by `version`.
5. Return `201 Created { productId }`.

> Products are always created in `NOT_ACTIVE` state. This allows the catalog manager to prepare the listing before publishing it to customers.

**Edge cases:**
- `categoryId` does not exist in the database → `422 Unprocessable Entity` ("Category not found")
- `basePrice` ≤ 0 → `400 Bad Request`
- `title` or `description` blank → `400 Bad Request`

**External calls:** none (synchronous); event published asynchronously
**Emits:** `ProductCreated { eventId, productId, title, categoryId, basePrice, status, version, timestamp }` → consumed by Inventory Service (EP-INV-EVT-01)
**Refs:** US-CM-01, SCN-CM01, N-01, N-02

---

## EP-CAT-02: Update Product Information

**Type:** `HTTP PUT /catalog/products/{id}`
**Caller:** Catalog Manager (via API Gateway)
**Auth:** `ROLE_CATALOG_MANAGER`

**Steps:**
1. Verify that the product with `id` exists. If not → `404`.
2. Validate request body: `title` and `description` non-empty; `categoryId` valid UUID; `basePrice` > 0.
3. Verify that the target `categoryId` exists. If not → `422`.
4. Update `Product.title`, `Product.description`, `Product.categoryId`, `Product.basePrice`, and flush (the version increases if a value changed).
5. In the same transaction, store `ProductUpdated` with the product's state after the change in the outbox (see *Product Events*).
6. Return `200 OK` with the updated product (the full view `{ productId, title, description, categoryId, basePrice, status, stockStatus }`).

> Price changes affect **only future orders**. Past orders retain their `OrderItemSnapshot` prices unchanged.

**Edge cases:**
- Product `id` not found → `404 Not Found`
- `categoryId` not found → `422 Unprocessable Entity`
- `basePrice` ≤ 0 → `400 Bad Request`
- An overlapping save of the same product committed first → `409 Conflict`; nothing is changed and no event is stored (*Product version*)
- No value changed → `200 OK`; the event repeats the stored version and consumers ignore it

**External calls:** none
**Emits:** `ProductUpdated { eventId, productId, title, categoryId, basePrice, status, version, timestamp }` → consumed by Inventory Service (EP-INV-EVT-03)
**Refs:** US-CM-04, SCN-CM04

---

## EP-CAT-03: Change Product Status (Activate / Deactivate)

**Type:** `HTTP PATCH /catalog/products/{id}/status`
**Caller:** Catalog Manager (via API Gateway)
**Auth:** `ROLE_CATALOG_MANAGER`

**Steps:**
1. Verify that the product with `id` exists. If not → `404`.
2. Parse request body: `status` must be one of `{ ACTIVE, NOT_ACTIVE }`. If neither → `400`.
3. **If `status == ACTIVE`** (activation path):
   a. Call Inventory Service synchronously: `GET /inventory/stock/{id}/readiness`.
   b. Inventory Service checks: `StockItem` exists for `productId` AND all packaging dimensions (grossWeightGrams, lengthCm, widthCm, heightCm) are set and non-null.
   c. If readiness check fails → return `422 Unprocessable Entity` with the reason code (Inventory's `reason`, e.g. `"packaging_data_missing"`; `"stock_item_not_found"` when Inventory answers `404`; unknown codes are passed through). Do **not** change the status.
   d. If readiness check passes → set `Product.status = ACTIVE`.
   e. Return `200 OK`.
4. **If `status == NOT_ACTIVE`** (deactivation / soft-delete path):
   a. Set `Product.status = NOT_ACTIVE` immediately. No external calls required.
   b. Return `200 OK`.
5. If the status actually changed (step 3d or 4a), store `ProductUpdated` with the product's state after the change in the outbox, in the same transaction (see *Product Events*).

> The status change is announced by `ProductUpdated`, so Inventory can show each stock row's product status (D6). Services that must decide on the current status (Order Service at checkout) still ask synchronously through EP-CAT-09, because an event can arrive late. Hard deletes are **forbidden**; deactivation is the only removal mechanism, preserving order history integrity.

**Edge cases:**
- Product `id` not found → `404 Not Found`
- `status` value not recognised → `400 Bad Request`
- Activation: Inventory Service returns `404` for the product → `422 Unprocessable Entity` ("StockItem not found — stock must be initialized before activation")
- Activation: Inventory Service is unavailable (connection error, timeout, `5xx`, or an unexpected status such as `401`/`403`) → `503 Service Unavailable` with a `Retry-After` hint; the status is not changed. Connection errors, timeouts and `5xx` are retried once before giving up (timeouts 2 s connect / 3 s read); no circuit breaker (decision in catalog task 22)
- Activation of an already `ACTIVE` product → the readiness check runs again; if Inventory says "not ready", `422` and the product stays `ACTIVE`
- Deactivation: product is already `NOT_ACTIVE` → idempotent success; return `200 OK`; no event
- An overlapping save of the same product committed first → `409 Conflict`; the status is not changed and no event is stored (*Product version*)

**External calls:**
- Inventory Service: `GET /inventory/stock/{productId}/readiness` — purpose: validate stock & packaging readiness — **sync** (activation path only)

**Emits:** `ProductUpdated { eventId, productId, title, categoryId, basePrice, status, version, timestamp }` when the status changed → consumed by Inventory Service (EP-INV-EVT-03)
**Refs:** US-CM-02, US-CM-03, SCN-CM02, SCN-CM03, N-02, N-03

---

## EP-CAT-04: Browse & Filter Products (Public / Staff View)

**Type:** `HTTP GET /catalog/products`
**Caller:** Customer (unauthenticated or `ROLE_CUSTOMER`) — or — staff: Catalog Manager (`ROLE_CATALOG_MANAGER`), Inventory Worker (`ROLE_INVENTORY_WORKER`)
**Auth:** None required; role determines which products are visible (*Product visibility*)

**Steps:**
1. Parse optional query parameters: `search` (title/description keyword), `categoryId`, `minPrice`, `maxPrice`, `ids`, `sortBy`, `page`, `size`. `ids` is a comma-separated list of product UUIDs (D4): only those products are returned, at most 100 distinct IDs. `sortBy` is `field` or `field,direction` with field `title` or `basePrice` and direction `asc` (default) or `desc`; the default order is by `title`. `page` is zero-based; `size` defaults to `20` (maximum `100`).
2. Determine caller role from the authenticated principal (set from the `X-User-Role` header; none for anonymous) and apply *Product visibility*:
   - **Customer / anonymous / any non-staff role**: filter to `status = ACTIVE` only.
   - **Staff (`CATALOG_MANAGER`, `INVENTORY_WORKER`)**: no status filter — all products (`ACTIVE` and `NOT_ACTIVE`) are returned. An additional optional `status` query param may be used to filter.
3. Execute the filtered, paginated query against the `products` table. All filters, `ids` included, are combined with AND, together with the visibility filter.
4. For each product in the result, include the `stockStatus` read-model field (`IN_STOCK` or `OUT_OF_STOCK`) directly from the stored column (no Inventory call required).
5. Return `200 OK { content: [ { productId, title, basePrice, categoryId, status, stockStatus } ], page, size, totalElements }` — the shared paging envelope ([api-contracts.md](../services-requirements/api-contracts.md#shared-conventions)); `size` is the effective page size (the requested one, or `20`).

**Edge cases:**
- `minPrice` > `maxPrice` → `400 Bad Request`
- Invalid `status`, `sortBy`, `page` or `size` → `400 Bad Request`
- A non-staff caller sends `status` → the parameter is ignored (only `ACTIVE` products are returned)
- `categoryId` not found → return empty result (not a 404; a non-existent category simply matches nothing)
- No products match the filters → return `200 OK` with empty `content` array
- `ids` empty, containing a malformed UUID or an empty element (`a,,b`), or with more than 100 distinct IDs → `400 Bad Request` (`errors.ids`); the same checks as EP-CAT-09
- `ids` with duplicates → each product appears once; unknown IDs, and inactive products for a non-staff caller, are simply absent
- `ids` does not change paging: a caller who wants all of up to 100 products in one page sends `size=100`

**External calls:** none
**Emits:** none
**Refs:** US-CUST-01, US-CM-06, US-INV-04, SCN-C05

---

## EP-CAT-05: Get Product Details

**Type:** `HTTP GET /catalog/products/{id}`
**Caller:** Customer (unauthenticated or `ROLE_CUSTOMER`) — or — staff: Catalog Manager (the edit form needs the description of a product being prepared), Inventory Worker
**Auth:** None required; role determines which products are visible (*Product visibility*, D1). The gateway route is `Optional` (D3)

**Steps:**
1. Look up `Product` by `id`.
2. If the product does not exist → `404 Not Found`.
3. If the product exists, `status = NOT_ACTIVE`, and the caller is not staff → `404 Not Found` (hidden from customers).
4. Return `200 OK { productId, title, description, categoryId, basePrice, status, stockStatus }` — the full view, the same shape for every caller (D2). Customers only ever get `status: ACTIVE`.

**Edge cases:**
- Product not found → `404 Not Found`
- Product found but `NOT_ACTIVE`, caller anonymous or not staff → `404 Not Found` (do not leak the existence of inactive products to customers)
- Product found but `NOT_ACTIVE`, caller staff → `200 OK` with `status: NOT_ACTIVE`
- An invalid or expired token on this path → `401` from the gateway (behaviour of `Optional` routes); the storefront sends no token on catalog reads

**External calls:** none
**Emits:** none
**Refs:** US-CUST-02, US-CM-04, SCN-C06

---

## EP-CAT-06: Create Category

**Type:** `HTTP POST /catalog/categories`
**Caller:** Catalog Manager (via API Gateway)
**Auth:** `ROLE_CATALOG_MANAGER`

**Steps:**
1. Validate request body: `name` must be non-empty.
2. Check that no category with the same `name` already exists (name must be unique).
3. Persist `Category(id=UUID, name)`.
4. Return `201 Created { categoryId, name }`.

**Edge cases:**
- `name` is blank → `400 Bad Request`
- A category with that `name` already exists → `409 Conflict` ("Category name already in use")

**External calls:** none
**Emits:** none
**Refs:** US-CM-05, SCN-CM05

---

## EP-CAT-07: Rename Category

**Type:** `HTTP PUT /catalog/categories/{id}`
**Caller:** Catalog Manager (via API Gateway)
**Auth:** `ROLE_CATALOG_MANAGER`

**Steps:**
1. Verify that category with `id` exists. If not → `404`.
2. Validate request body: `name` non-empty.
3. Check that no other category with the same `name` already exists.
4. Update `Category.name`.
5. Return `200 OK { categoryId, name }`.

**Edge cases:**
- Category `id` not found → `404 Not Found`
- New `name` is blank → `400 Bad Request`
- Another category already has that `name` → `409 Conflict`

**External calls:** none
**Emits:** none
**Refs:** US-CM-05, SCN-CM06

---

## EP-CAT-08: Delete Category

**Type:** `HTTP DELETE /catalog/categories/{id}`
**Caller:** Catalog Manager (via API Gateway)
**Auth:** `ROLE_CATALOG_MANAGER`

**Steps:**
1. Verify that category with `id` exists. If not → `404`.
2. Count products currently assigned to this `categoryId`.
3. If count > 0 → return `409 Conflict` ("Category has associated products. Reassign or remove all products before deletion.").
4. If count == 0 → delete the `Category` record.
5. Return `200 OK`.

**Edge cases:**
- Category `id` not found → `404 Not Found`
- One or more products are assigned → `409 Conflict`

**External calls:** none
**Emits:** none
**Refs:** US-CM-05, SCN-CM07, SCN-CM08

---

## EP-CAT-09: Batch Get Products by IDs (Internal)

**Type:** `HTTP GET /catalog/products/batch-get?ids={uuid1,uuid2,...}`
**Caller:** Order Service (internal, during order submission to capture price snapshots); Inventory Service (existence check in EP-INV-02/03 and the product-data backfill EP-INV-JOB-01)
**Auth:** Internal (no JWT; service-to-service trust)

**Steps:**
1. Parse the `ids` query parameter as a comma-separated list of UUIDs.
2. Query all `Product` records whose `id` is in the list.
3. Return `200 OK [ { productId, title, basePrice, status, stockStatus, version } ]` — a flat array, including the current `status` field so Order Service can also validate `ACTIVE` status in the same call, and the `stockStatus` read model (`IN_STOCK` / `OUT_OF_STOCK`, added 2026-10-16) so Order's checkout soft check (EP-ORD-01) needs no Inventory call. `stockStatus` is the stored column, updated from Inventory's stock events; it is eventually consistent and says nothing about quantities. `version` (added 2026-10-22, D7) is the product's current version (*Product version*), so Inventory can store the answer with the same "newer version wins" rule it applies to product events.
4. If some requested `productId` values are not found in the catalog, they are simply absent from the array (do not fail the entire request). The caller detects missing products by comparing IDs.

> **Agreed change:** an earlier version of this card returned a wrapper object with a `products` array and a separate `notFound` list. The team dropped the wrapper and the `notFound` list; the contract is the flat array above (see [api-contracts.md](../services-requirements/api-contracts.md)).

**Edge cases:**
- Missing or empty `ids`, or a malformed UUID in the list → `400 Bad Request`
- More than 100 distinct IDs → `400 Bad Request`
- Duplicate IDs → each product appears once
- All requested IDs not found → `200 OK` with `[]`

**External calls:** none
**Emits:** none
**Refs:** SCN-C14 (step 2), api-contracts.md

---

## EP-CAT-10: List & Search Categories

**Type:** `HTTP GET /catalog/categories`
**Caller:** Anyone: Catalog Manager (to pick a `categoryId` for a product), Customer / anonymous (for the `categoryId` filter of EP-CAT-04)
**Auth:** None

> Added to the original spec by team agreement: without it, category IDs could not be discovered.

**Steps:**
1. Parse optional query parameters: `search` (case-insensitive substring of `name`), `page` (zero-based, default `0`), `size` (default `20`, maximum `100`).
2. Query categories ordered by `name` (then `id`), applying `search` if present. `%` and `_` in `search` are matched literally.
3. Return `200 OK { content: [ { categoryId, name } ], page, size, totalElements }` (the same paging envelope as EP-CAT-04; `size` is the effective page size).

**Edge cases:**
- No category matches → `200 OK` with empty `content`
- Negative `page`, `size` ≤ 0 or `size` > 100, non-numeric values → `400 Bad Request`

**External calls:** none
**Emits:** none
**Refs:** US-CM-05, EP-CAT-04

---

## EP-CAT-EVT-01: Consume `ProductStockReplenished`

**Type:** Event consumer
**Producer:** Inventory Service
**Trigger:** `availableQuantity` for an `ACTIVE` product transitions from `0` to `> 0` (restocking, stock adjustment, or reservation release).

**Payload received:** `{ productId, timestamp }` on topic `inventory.stock-events` (key `productId`, header `eventType`), consumer group `catalog-service`. Undeserializable messages, and messages still failing after bounded retries, go to the dead-letter topic `catalog.stock-events-dlt`.

**Steps:**
1. Receive and deserialise the `ProductStockReplenished` event.
2. Look up `Product` by `productId`. If not found → log a warning and acknowledge (do not fail the consumer).
3. Update `Product.stockStatus = IN_STOCK`.
4. Acknowledge the message.

**Edge cases:**
- `productId` unknown in catalog → log warning and acknowledge (defensive; should not occur in a healthy system)
- Product already marked `IN_STOCK` → idempotent update; acknowledge

**External calls:** none
**Refs:** SCN-S03, customer-technical-insights §1

---

## EP-CAT-EVT-02: Consume `ProductStockDepleted`

**Type:** Event consumer
**Producer:** Inventory Service
**Trigger:** `availableQuantity` for an `ACTIVE` product transitions from `> 0` to `0` (reservation, write-off, or adjustment).

**Payload received:** `{ productId, timestamp }` on topic `inventory.stock-events` (key `productId`, header `eventType`), consumer group `catalog-service`. Undeserializable messages, and messages still failing after bounded retries, go to the dead-letter topic `catalog.stock-events-dlt`.

**Steps:**
1. Receive and deserialise the `ProductStockDepleted` event.
2. Look up `Product` by `productId`. If not found → log a warning and acknowledge.
3. Update `Product.stockStatus = OUT_OF_STOCK`.
4. Acknowledge the message.

**Edge cases:**
- `productId` unknown → log warning and acknowledge
- Product already marked `OUT_OF_STOCK` → idempotent update; acknowledge

**External calls:** none
**Refs:** SCN-S02, customer-technical-insights §1

---

## Revisions

### 2026-10-22: Staff visibility, product version and full-state product events

Prompted by the frontend backlog items BL-01 (catalog managers cannot load an inactive product's description) and BL-02 (inventory workers cannot see the titles of inactive products). Both had one root cause: the read API was designed for customers, while staff work almost entirely on `NOT_ACTIVE` products. Searching and sorting the stock list by title also needs the title inside Inventory, which this revision provides through product events.

**Decisions** (D8–D10 are in the [Inventory spec](inventory-service-spec.md#revision-2026-10-22-copy-of-product-data) revision of the same date):

| # | Decision | Reason |
|---|---|---|
| D1 | One visibility rule for EP-CAT-04 and EP-CAT-05: `CATALOG_MANAGER` and `INVENTORY_WORKER` see all statuses; everyone else (`ADMIN` included) only `ACTIVE` | One rule in one place; a worker who opens a product from the list must not get `404`. `ADMIN` has no catalog job in any user story |
| D2 | EP-CAT-05 returns the full view `{ productId, title, description, categoryId, basePrice, status, stockStatus }` to every caller; `ProductDetailsResponse` is removed | One response shape per endpoint; a separate staff path (`/catalog/manage/...`) was rejected because the list already applies the role on the shared path |
| D3 | Gateway route `GET /catalog/products/{id}` changes from `Public` to `Optional` | Catalog can only apply D1 if the gateway forwards the role |
| D4 | EP-CAT-04 gets an `ids` filter (at most 100 distinct IDs, combined with every other filter and the visibility rule) | Lets a client look up the products of one page of another service's list in one call |
| D5 | `Product.version` with JPA `@Version`; overlapping saves → `409`; `@DynamicUpdate` on `Product` | Orders product events for consumers; closes the lost update where a save could write back a stale `stockStatus` |
| D6 | Product events carry the full state `{ eventId, productId, title, categoryId, basePrice, status, version, timestamp }`; new `ProductUpdated` after EP-CAT-02 and after an EP-CAT-03 that changes the status | Inventory keeps a copy of title and status (D8) for searching and sorting stock rows. A full-state event is idempotent by content and serves later needs without a new event type (preferred over a narrow `ProductRenamed`) |
| D7 | EP-CAT-09 adds `version` to its answer (additive) | Inventory fills missing copies from batch-get with the same "newer version wins" rule as for events (D9) |

**Changes from the earlier version of this spec:**

| Area | Before | Now |
|---|---|---|
| EP-CAT-04 visibility | `NOT_ACTIVE` visible to `CATALOG_MANAGER` only | visible to staff (`CATALOG_MANAGER`, `INVENTORY_WORKER`) |
| EP-CAT-04 filters | — | `ids` (comma-separated, ≤ 100) |
| EP-CAT-05 | `ACTIVE` only for everyone; `{ productId, title, description, categoryId, basePrice, stockStatus }` | staff see `NOT_ACTIVE` too; full view with `status` |
| `Product` | no version | `version` (`@Version`), `@DynamicUpdate` |
| `ProductCreated` payload | `{ eventId, productId, timestamp }` | full product state (additive) |
| Product events | `ProductCreated` only; deactivation published nothing | also `ProductUpdated` (EP-CAT-02, status changes of EP-CAT-03) |
| EP-CAT-02/03 errors | — | `409` on an overlapping save |
| EP-CAT-09 answer | `{ productId, title, basePrice, status, stockStatus }` | adds `version` |

Tasks: [27](../../tasks/catalog-service/27-staff-product-visibility.md) (D1, D2), [28](../../tasks/catalog-service/28-browse-products-ids-filter.md) (D4), [29](../../tasks/catalog-service/29-product-version-and-full-state-events.md) (D5–D7); gateway [task 12](../../tasks/api-getaway/12-optional-product-details-route.md) (D3).

**Other documents updated with this revision (2026-10-22):** `api-contracts.md` (EP-CAT-04, EP-CAT-05, EP-CAT-09, EP-INV-01), `events-catalogue.md` (`ProductCreated` payload, new `ProductUpdated`), `entities.md` (`Product.version`, the copied product fields of `StockItem`), the API Gateway spec (route table), the Inventory spec (copy of product data, D8–D10), and the frontend backlog items BL-01 and BL-02 (backend decision notes).

*Implemented 2026-10-22 in Catalog (tasks 27–29), the gateway (task 12, D3) and Inventory (tasks 22–24, D8–D10).* Implementation details worth knowing:
- The visibility rule is `ProductService.visibleStatus`; EP-CAT-05 calls it with no requested status, so both endpoints share one rule. EP-CAT-05 carries `@OptionalAuthentication` in `public.json`.
- EP-CAT-04 and EP-CAT-09 validate `ids` with the same helper (`ProductService.distinctIds`).
- Both product events use one payload record, `events/ProductEvent` (constants `CREATED`, `UPDATED`); the service flushes before building it. EP-CAT-03 returns without a write when the product already has the requested status.
- `409` uses the problem type `conflict`. Flyway `V4__add_product_version.sql` adds the column with default `0` for existing products.

### 2026-10-21: Paging envelope and OpenAPI document

- EP-CAT-04 and EP-CAT-10 answer the shared paging envelope `{ content, page, size, totalElements }`: `size` was added, matching Order and Inventory (frontend backlog BL-05, [task 25](../../tasks/catalog-service/25-uniform-paging-envelope.md)). The change is additive.
- Catalog publishes OpenAPI documents generated from its code and is the pilot for the conventions in [OpenAPI Conventions](../services-requirements/openapi-conventions.md) (frontend backlog BL-04, [task 26](../../tasks/catalog-service/26-openapi-document.md)).

*Implemented 2026-10-22 ([task 25](../../tasks/catalog-service/25-uniform-paging-envelope.md), [task 26](../../tasks/catalog-service/26-openapi-document.md)): the committed documents are `catalog-service/openapi/public.json` (9 operations) and `internal.json` (EP-CAT-09). The pilot's building blocks, including the Catalog-only `@OptionalAuthentication` for EP-CAT-04, are described in [OpenAPI Conventions](../services-requirements/openapi-conventions.md#building-blocks-per-service).*

**Other documents updated with this revision (2026-10-21):** `api-contracts.md` (shared conventions section, `size` in the Catalog list responses), `security-model.md` (documentation paths), the Auth, Customer, Inventory, Order and Delivery specs (link to the OpenAPI conventions), and the new `openapi-conventions.md`.
