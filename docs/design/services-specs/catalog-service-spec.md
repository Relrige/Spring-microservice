# Catalog Service — Implementation Spec

Owns the product catalog, category hierarchy, current base prices, and a coarse stock-availability read-model. It is the **master domain** for products: it generates `productId` (UUID) which all downstream services reference. It never stores stock quantities or packaging data — those belong to Inventory Service.

The stock-availability read-model (`Product.stockStatus`) is updated asynchronously via Inventory events; Catalog Service never queries Inventory on read paths.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `Category` (`id`, `name`); `Product` (`id`, `title`, `description`, `categoryId`, `basePrice`, `status`, `stockStatus`) |
| **Key enums** | `ProductStatus { ACTIVE, NOT_ACTIVE }`; `StockStatus { IN_STOCK, OUT_OF_STOCK }` |
| **Events emitted** | `ProductCreated` |
| **Events consumed** | `ProductStockReplenished`, `ProductStockDepleted` (both from Inventory Service) |

---

## EP-CAT-01: Create Product

**Type:** `HTTP POST /catalog/products`
**Caller:** Catalog Manager (via API Gateway)
**Auth:** `ROLE_CATALOG_MANAGER`

**Steps:**
1. Validate request body: `title` non-empty; `description` non-empty; `categoryId` valid UUID; `basePrice` > 0.
2. Verify that `categoryId` exists in the `categories` table. If not → `422`.
3. Persist `Product(id=UUID, title, description, categoryId, basePrice, status=NOT_ACTIVE, stockStatus=OUT_OF_STOCK)`.
4. In the same transaction, store the event `ProductCreated { eventId, productId, timestamp }` in the outbox table; a relay publishes it to the message broker (topic `catalog.product-events`, key `productId`) after the commit. Delivery is at-least-once (transactional outbox), so consumers deduplicate by `eventId`.
5. Return `201 Created { productId }`.

> Products are always created in `NOT_ACTIVE` state. This allows the catalog manager to prepare the listing before publishing it to customers.

**Edge cases:**
- `categoryId` does not exist in the database → `422 Unprocessable Entity` ("Category not found")
- `basePrice` ≤ 0 → `400 Bad Request`
- `title` or `description` blank → `400 Bad Request`

**External calls:** none (synchronous); event published asynchronously
**Emits:** `ProductCreated { eventId, productId, timestamp }` → consumed by Inventory Service (EP-INV-EVT-01)
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
4. Update `Product.title`, `Product.description`, `Product.categoryId`, `Product.basePrice`.
5. Return `200 OK` with the updated product.

> Price changes affect **only future orders**. Past orders retain their `OrderItemSnapshot` prices unchanged.

**Edge cases:**
- Product `id` not found → `404 Not Found`
- `categoryId` not found → `422 Unprocessable Entity`
- `basePrice` ≤ 0 → `400 Bad Request`

**External calls:** none
**Emits:** none
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

> Deactivation does not publish a domain event. Downstream services (Order Service, Catalog browsing) query product status synchronously or via the existing API when they need to check it. Hard deletes are **forbidden**; deactivation is the only removal mechanism, preserving order history integrity.

**Edge cases:**
- Product `id` not found → `404 Not Found`
- `status` value not recognised → `400 Bad Request`
- Activation: Inventory Service returns `404` for the product → `422 Unprocessable Entity` ("StockItem not found — stock must be initialized before activation")
- Activation: Inventory Service is unavailable (connection error, timeout, `5xx`, or an unexpected status such as `401`/`403`) → `503 Service Unavailable` with a `Retry-After` hint; the status is not changed. Connection errors, timeouts and `5xx` are retried once before giving up (timeouts 2 s connect / 3 s read); no circuit breaker (decision in catalog task 22)
- Activation of an already `ACTIVE` product → the readiness check runs again; if Inventory says "not ready", `422` and the product stays `ACTIVE`
- Deactivation: product is already `NOT_ACTIVE` → idempotent success; return `200 OK`

**External calls:**
- Inventory Service: `GET /inventory/stock/{productId}/readiness` — purpose: validate stock & packaging readiness — **sync** (activation path only)

**Emits:** none
**Refs:** US-CM-02, US-CM-03, SCN-CM02, SCN-CM03, N-02, N-03

---

## EP-CAT-04: Browse & Filter Products (Public / Manager View)

**Type:** `HTTP GET /catalog/products`
**Caller:** Customer (unauthenticated or `ROLE_CUSTOMER`) — or — Catalog Manager (`ROLE_CATALOG_MANAGER`)
**Auth:** None required; role determines which products are visible

**Steps:**
1. Parse optional query parameters: `search` (title/description keyword), `categoryId`, `minPrice`, `maxPrice`, `sortBy`, `page`, `size`. `sortBy` is `field` or `field,direction` with field `title` or `basePrice` and direction `asc` (default) or `desc`; the default order is by `title`. `page` is zero-based; `size` defaults to `20` (maximum `100`).
2. Determine caller role from the `X-User-Role` header (or absent header for anonymous):
   - **Customer / anonymous**: filter to `status = ACTIVE` only.
   - **Catalog Manager**: no status filter — all products (`ACTIVE` and `NOT_ACTIVE`) are returned. An additional optional `status` query param may be used to filter.
3. Execute the filtered, paginated query against the `products` table.
4. For each product in the result, include the `stockStatus` read-model field (`IN_STOCK` or `OUT_OF_STOCK`) directly from the stored column (no Inventory call required).
5. Return `200 OK { content: [ { productId, title, basePrice, categoryId, status, stockStatus } ], page, totalElements }`.

**Edge cases:**
- `minPrice` > `maxPrice` → `400 Bad Request`
- Invalid `status`, `sortBy`, `page` or `size` → `400 Bad Request`
- A non-manager caller sends `status` → the parameter is ignored (only `ACTIVE` products are returned)
- `categoryId` not found → return empty result (not a 404; a non-existent category simply matches nothing)
- No products match the filters → return `200 OK` with empty `content` array

**External calls:** none
**Emits:** none
**Refs:** US-CUST-01, US-CM-06, SCN-C05

---

## EP-CAT-05: Get Product Details

**Type:** `HTTP GET /catalog/products/{id}`
**Caller:** Customer (unauthenticated or `ROLE_CUSTOMER`)
**Auth:** None

**Steps:**
1. Look up `Product` by `id`.
2. If the product does not exist → `404 Not Found`.
3. If the product exists but `status = NOT_ACTIVE` → `404 Not Found` (hidden from customer-facing API; managers use EP-CAT-04 to view inactive products).
4. Return `200 OK { productId, title, description, categoryId, basePrice, stockStatus }`.

**Edge cases:**
- Product not found → `404 Not Found`
- Product found but `NOT_ACTIVE` → `404 Not Found` (do not leak the existence of inactive products to customers)

**External calls:** none
**Emits:** none
**Refs:** US-CUST-02, SCN-C06

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
**Caller:** Order Service (internal, during order submission to capture price snapshots)
**Auth:** Internal (no JWT; service-to-service trust)

**Steps:**
1. Parse the `ids` query parameter as a comma-separated list of UUIDs.
2. Query all `Product` records whose `id` is in the list.
3. Return `200 OK [ { productId, title, basePrice, status, stockStatus } ]` — a flat array, including the current `status` field so Order Service can also validate `ACTIVE` status in the same call, and the `stockStatus` read model (`IN_STOCK` / `OUT_OF_STOCK`, added 2026-10-16) so Order's checkout soft check (EP-ORD-01) needs no Inventory call. `stockStatus` is the stored column, updated from Inventory's stock events; it is eventually consistent and says nothing about quantities.
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
3. Return `200 OK { content: [ { categoryId, name } ], page, totalElements }` (the same paging envelope as EP-CAT-04).

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
