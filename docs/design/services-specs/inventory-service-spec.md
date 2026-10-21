# Inventory Service — Implementation Spec

Owns stock counts, physical packaging dimensions, and stock reservations. It represents the **warehouse context** of a product: `StockItem` is a distinct aggregate from `Catalog.Product`, even though both reference the same `productId`. Catalog Service knows about commercial presentation; Inventory Service knows about physical warehouse reality.

It serves both the **Inventory Worker** (human-facing endpoints) and other **internal services** (machine-facing endpoints for reservations and readiness checks).

> **Revision note (after inventory tasks 01–20).** This spec records the decisions taken during implementation. Where they differ from the first version of this spec, the change is listed under *Changes from the first spec version* at the end, with the task that made the decision.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `StockItem` (`productId`, `availableQuantity`, `reservedQuantity`, `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`); `StockItemReservation` (`id`, `orderId`, `productId`, `quantity`, `status`, `createdAt`, `updatedAt`) |
| **Key enums** | `ReservationStatus { ACTIVE, RELEASED, CONSUMED }` (`RELEASED` and `CONSUMED` are terminal) |
| **Derived values** (computed, never stored) | `totalQuantity = availableQuantity + reservedQuantity`; `packagingConfigured` = all four packaging values non-null and `> 0` |
| **Events emitted** | `ProductStockReplenished`, `ProductStockDepleted` on topic `inventory.stock-events` (see *Stock Events*) |
| **Events consumed** | `ProductCreated` (from Catalog Service), `OrderShipped` (from Order Service) |
| **Synchronous dependencies** | Catalog Service `GET /catalog/products/batch-get` (existence check, EP-INV-02/03 only; see *Catalog existence check*) |
| **Technical tables** (not part of the domain model) | `outbox_events` (transactional outbox for stock events), `processed_events` (Idempotent Consumer keys of `ProductCreated`) |

---

## Cross-cutting Conventions

### Security and routing

Authentication follows the [Security Model](../services-requirements/security-model.md): the API Gateway validates the JWT and forwards `X-User-Id` / `X-User-Role`; internal callers send the shared `X-Internal-Token` and get role `SERVICE`. Every route has an explicit rule (method + path); anything else is denied (`denyAll`). The role check runs **before** the request body is parsed or validated.

| Route | Role | Routed by the gateway |
|---|---|---|
| `GET /inventory/stock` | `INVENTORY_WORKER` | yes |
| `POST /inventory/stock/{productId}/adjust` | `INVENTORY_WORKER` | yes |
| `PUT /inventory/stock/{productId}/packaging` | `INVENTORY_WORKER` | yes |
| `GET /inventory/stock/{productId}/readiness` | `SERVICE` | no (internal) |
| `GET /inventory/stock/{productId}/packaging` | `SERVICE` | no (internal) |
| `POST /inventory/reservations` | `SERVICE` | no (internal) |
| `POST /inventory/reservations/{orderId}/release` | `SERVICE` | no (internal) |

Roles are not hierarchical: `ADMIN` gets `403` on worker and internal routes. Missing or invalid credentials → `401`; wrong role → `403`. `/actuator/health/**` (liveness and readiness probes) is open, so the probes are never blocked by the security chain.

A request's `X-Correlation-Id` is kept in the logging context (a new ID is generated when the header is missing) and forwarded on the outgoing Catalog call.

### Responses and errors

- A successful call with nothing to return answers **`204 No Content`** (packaging update, readiness, reserve, release).
- All errors use the RFC 9457 `ProblemDetail` shape shared by all services: `type`, `title`, `status`, `detail`, `timestamp`, `service`. `type` is `https://voltstore.com/errors/{validation | not-found | insufficient-stock | conflict | packaging-not-configured | service-unavailable | unauthorized | forbidden | internal-error}`.
- Extra properties: `errors` (`400`, map of field or parameter → messages), `insufficientItems` (`422` of EP-INV-06), `reason` (`422` of EP-INV-04/05).
- The `503` of EP-INV-02/03 has a fixed `detail` ("The product cannot be checked in the catalog right now. Please try again later.") and the header `Retry-After: 10`; hosts, Catalog's status and causes are only logged.
- `400` = malformed request: invalid JSON, a field rule broken, a non-UUID path variable, a non-integer number (for example `1.5` for an integer field). `422` = a well-formed request that cannot be done in the current state. Unexpected errors → `500` with a generic message; the stack trace is only logged.
- **OpenAPI:** the API is also described by an OpenAPI document generated from the code, `inventory-service/openapi/public.json` and `internal.json` ([OpenAPI Conventions](../services-requirements/openapi-conventions.md)); its error responses reference the named problem schemas listed there.

| Error | Status | Extra property |
|---|---|---|
| `StockItem` not found (EP-INV-04, EP-INV-05) | `404` | — |
| Write-off below zero (EP-INV-02) | `422` | — |
| Packaging not configured (EP-INV-04, EP-INV-05) | `422` | `reason: "packaging_data_missing"` |
| Insufficient stock for a reservation (EP-INV-06) | `422` | `insufficientItems` |
| Order already has `ACTIVE` reservations (EP-INV-06) | `409` | — |
| Product not found in Catalog (EP-INV-02/03) | `404` (type `not-found`) | — |
| Catalog unavailable (EP-INV-02/03) | `503` (type `service-unavailable`) | header `Retry-After: 10` |

The `reason` value is a contract with Catalog and Delivery and must stay stable.

### Concurrency and data integrity

- **Pessimistic row locks.** Every stock change loads the affected `StockItem` rows with `SELECT … FOR UPDATE`. Concurrent writers to one product wait instead of failing, so no retry logic is needed.
- **Fixed lock order.** Operations that lock several rows lock them **sorted by `productId`**, so two transactions can never wait on each other in a cycle (deadlock).
- **`reservedQuantity` is stored**, not computed from reservations. Every reservation change updates `availableQuantity` and `reservedQuantity` together in one transaction.
- **Database guards** stay as the last line of defence: `CHECK (available_quantity >= 0)`, `CHECK (reserved_quantity >= 0)`, packaging columns null or `> 0`, `CHECK (quantity >= 1)` on reservations, a foreign key from reservations to stock items, and a partial unique index allowing at most one `ACTIVE` reservation per `(orderId, productId)`.
- **Race-safe creation.** A missing `StockItem` is created with `INSERT … ON CONFLICT (product_id) DO NOTHING` and then locked, so concurrent first-time requests produce exactly one row (a plain JPA `save()` with an assigned id would select-then-insert and race).
- Reservation rows are history and are never deleted.

### Stock Events

| | |
|---|---|
| **Topic** | `inventory.stock-events` (owned and declared by Inventory), 3 partitions, replication factor 1 |
| **Key** | `productId` — events of one product land in one partition and keep their order |
| **Headers** | `eventType` = `ProductStockReplenished` or `ProductStockDepleted` (both payloads have the same shape, so consumers pick the type from this header); `eventId` (the same value as in the payload) |
| **Payload** | `{ eventId, productId, timestamp }` — `eventId` is a mandatory UUID per event; `timestamp` is an ISO-8601 UTC instant |

- **Crossing rule** (the same for adjust, reserve and release): `availableQuantity` going from `0` to `> 0` → `ProductStockReplenished`; from `> 0` to `0` → `ProductStockDepleted`; any other change → no event. Events are emitted for every product, regardless of its Catalog status (Inventory does not know it).
- One transaction can emit several events (one per product that crossed zero in a reserve or release).
- **Delivery: transactional outbox** ([task 17](../../tasks/inventory-service/17-transactional-outbox.md)). The event is written to the `outbox_events` table **in the same transaction** as the stock change (one JPA transaction manager, one `DataSource`; no Kafka or XA transactions), so it exists if and only if the change was committed. A polling relay (every 500 ms, batches of 50) publishes the rows afterwards and marks a row published only after the broker acknowledged it. Delivery is **at-least-once**: a crash or a timed-out acknowledgment (10 s) re-sends the event; consumers deduplicate by `eventId` or are idempotent by state. No HTTP request ever calls Kafka, so a broker outage only delays events.
- **Per-product order** is preserved, also with several replicas: only one relay publishes at a time (each run holds a PostgreSQL transaction-level advisory lock; a replica without the lock skips the run). It sends rows in `sequence_number` order (an identity column) and waits for each acknowledgment. For one product the sequence order is the commit order, because the product's events are written while its stock row is locked.
- **Failures:** a row that cannot be published gets an exponential back-off (1 s, 2 s, 4 s, … up to 5 min) and is retried forever. Until it succeeds, the **later events of the same product are held back**; other products keep flowing. Each failed attempt is logged at `ERROR` (with how long the event has been waiting), and the gauge `inventory.outbox.oldest.unpublished.age` (seconds) shows a stuck relay.
- Published rows are deleted after 7 days by an hourly cleanup job.

### Catalog existence check

Used by EP-INV-02 and EP-INV-03 only when the product has **no `StockItem` yet**. Normally `ProductCreated` (EP-INV-EVT-01) has created it already, so this synchronous call is a rare fallback for a missed or late event ([task 19](../../tasks/inventory-service/19-catalog-product-existence-client.md)).

- **Call:** `GET /catalog/products/batch-get?ids={productId}` with `X-Internal-Token` (Catalog's internal endpoint, role `SERVICE`) and `X-Correlation-Id`; the caller's `X-User-Id` / `X-User-Role` are never forwarded. Answer `[ { productId, title, basePrice, status } ]`, read as a tolerant reader; only the presence of `productId` matters. An empty array means "not found".
- **Outside the transaction:** the check runs before the transaction that locks the stock row, so no database connection or row lock is held while waiting for Catalog. A `StockItem` created by someone else in the meantime is harmless, because the creation is race-safe.
- **Outcomes:** found → the `StockItem` is created and the operation continues; not found → `404`; connection error, timeout, `5xx` or an unexpected `4xx` (e.g. `401`/`403`, a configuration problem) → `503`. Nothing is changed on `404` or `503`.
- **Resilience** ([task 20](../../tasks/inventory-service/20-catalog-call-resilience.md), decision record there): connect timeout 2 s, read timeout 3 s, and **one retry** after 300 ms (+ up to 150 ms jitter) for connection errors, timeouts and `5xx` only. A "not found" answer and other `4xx` are never retried. No circuit breaker: the call is too rare for one to help. Worst case about 6.5 s before the `503`.
- **Logging:** every failed attempt once (`WARN` for transient failures, `ERROR` for an unexpected status), the final `503` once at `ERROR`; the internal token is never logged.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `INTERNAL_TOKEN` | — (required) | shared secret of service-to-service calls; must equal `AUTH_INTERNAL_TOKEN` of the other services |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | broker address (`kafka:9092` in Compose and Kubernetes) |
| `CATALOG_PRODUCT_EVENTS_TOPIC` | `catalog.product-events` | Catalog-owned topic with `ProductCreated` |
| `ORDER_EVENTS_TOPIC` | `order.order-events` (provisional) | Order-owned topic with `OrderShipped` |
| `CATALOG_SERVICE_URL` | `http://localhost:8081` | Catalog base URL (`http://catalog-service:8080` in Compose, `http://catalog-service:8081` in Kubernetes) |
| `CATALOG_CONNECT_TIMEOUT`, `CATALOG_READ_TIMEOUT`, `CATALOG_MAX_RETRIES`, `CATALOG_RETRY_DELAY` | `2s`, `3s`, `1`, `300ms` | Catalog call tuning (optional) |

The stock-events topic name is a code constant. Outbox timings (`inventory.outbox.*`) and the `processed_events` retention (`inventory.idempotency.*`, 30 days) are set in `application.yml`.

### List responses

Lists use the shared paging envelope `{ content, page, size, totalElements }` (`page` is zero-based). Query parameters: `page` (default `0`), `size` (default `20`, `1`–`100`), `sortBy=field` or `sortBy=field,asc|desc`. Invalid values → `400` with `errors.<parameter>`.

---

## EP-INV-01: View Stock Balances

**Type:** `HTTP GET /inventory/stock`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Query parameters:**
- `packagingConfigured=true|false` — optional; omitted = no filter. `false` returns items where any packaging value is null or `≤ 0`.
- `page`, `size`, `sortBy` — see *List responses*. Sortable fields: `productId` (default order), `availableQuantity`, `reservedQuantity`. `productId` is always the final tiebreaker, so the order is stable between requests.

**Steps:**
1. Query `StockItem` records, filtering and sorting in the database.
2. For each `StockItem`, compute `totalQuantity` and `packagingConfigured`.
3. Return `200 OK { content: [ { productId, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured } ], page, size, totalElements }`.

**Edge cases:**
- No stock items exist yet → `200 OK` with empty `content`
- Invalid boolean, `page < 0`, `size` outside `1`–`100`, unknown sort field or direction → `400`
- `totalQuantity` is computed in Java and cannot be sorted or filtered

**External calls:** none
**Emits:** none
**Refs:** US-INV-04, SCN-INV03

---

## EP-INV-02: Adjust Stock Quantity (Restock / Write-off)

**Type:** `HTTP POST /inventory/stock/{productId}/adjust`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Steps:**
1. Validate request body `{ delta }`: required, an integer, non-zero (positive for restocking, negative for write-off / correction). There is no `reason` field.
2. Check if a `StockItem` exists for `productId`.
   - **If exists**: proceed to step 3.
   - **If not exists**: call Catalog Service synchronously: `GET /catalog/products/batch-get?ids={productId}` (internal endpoint; returns the product in any status, including `NOT_ACTIVE`). The call is made **outside** the transaction that locks stock rows (see *Catalog existence check*).
     - Product not found in Catalog (empty `batch-get` result) → `404 Not Found`. Do not create a `StockItem` for a non-existent product.
     - Catalog unreachable, timeout, `5xx` or an unexpected `4xx` (after one retry for transient failures) → `503 Service Unavailable` with `Retry-After: 10`; nothing changes.
     - Product found → continue; the `StockItem` is created in step 3.
3. In one transaction: race-safely create `StockItem(productId, availableQuantity=0, reservedQuantity=0, packaging=null)` if it is still missing, lock the row, and validate that `availableQuantity + delta >= 0`.
4. Apply `availableQuantity += delta`.
5. If `availableQuantity` crossed zero, write the stock event to the outbox in the same transaction (see *Stock Events*).
6. Return `200 OK { newAvailableQuantity }`.

> Restocking is permitted even for `NOT_ACTIVE` products — warehouse inventory can be prepared before a product goes live. This is why the existence check uses Catalog's internal `batch-get` endpoint: the public `GET /catalog/products/{id}` hides `NOT_ACTIVE` products (EP-CAT-05), and every new product starts `NOT_ACTIVE`.

**Edge cases:**
- `delta` missing, `0` or not an integer → `400 Bad Request` ("Delta must be non-zero" for `0`)
- `availableQuantity + delta < 0` → `422 Unprocessable Entity` ("Insufficient available stock for write-off"); nothing changes, and no `StockItem` is left behind for a product that had none
- `productId` not found in Catalog → `404 Not Found`
- Catalog cannot answer → `503 Service Unavailable` (`Retry-After: 10`); no `StockItem` is created
- An existing `StockItem` never triggers a Catalog call
- Two concurrent first-time adjustments → one `StockItem`, both deltas applied
- Broker down → still `200`; the event waits in the outbox and is published when the broker is back

**External calls:**
- Catalog Service: `GET /catalog/products/batch-get?ids={productId}` — purpose: verify product existence when `StockItem` is absent — **sync** (only if `StockItem` does not already exist)

**Emits:**
- `ProductStockReplenished` → consumed by Catalog Service (EP-CAT-EVT-01), when the product crosses 0→>0
- `ProductStockDepleted` → consumed by Catalog Service (EP-CAT-EVT-02), when the product crosses >0→0

**Refs:** US-INV-01, US-INV-03, SCN-INV01, SCN-S02, SCN-S03

---

## EP-INV-03: Set / Update Packaging Logistics Parameters

**Type:** `HTTP PUT /inventory/stock/{productId}/packaging`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Steps:**
1. Validate request body `{ grossWeightGrams, lengthCm, widthCm, heightCm }`: all four are required positive integers. There are no upper bounds.
2. Check if a `StockItem` exists for `productId`; if not, do the same Catalog existence check and race-safe creation as EP-INV-02 step 2.
3. Lock the `StockItem` row and replace all four packaging values at once. Quantities are not changed.
4. Return `204 No Content`.

> Setting packaging is a **prerequisite for product activation**. Catalog Service calls EP-INV-04 (readiness check) before allowing activation, which verifies all four packaging values are set.

**Edge cases:**
- Any value missing, `≤ 0` or not an integer → `400 Bad Request` with field errors
- `productId` not found in Catalog → `404 Not Found`
- Catalog cannot answer → `503 Service Unavailable` (`Retry-After: 10`); no `StockItem` is created
- Repeating the call with the same body has the same result (idempotent)

**External calls:**
- Catalog Service: `GET /catalog/products/batch-get?ids={productId}` — purpose: verify product existence when `StockItem` is absent — **sync** (only if `StockItem` does not already exist)

**Emits:** none
**Refs:** US-INV-02, SCN-INV02, N-03

---

## EP-INV-04: Check Stock Readiness (Internal — Used at Product Activation)

**Type:** `HTTP GET /inventory/stock/{productId}/readiness`
**Caller:** Catalog Service (internal, called during EP-CAT-03 activation flow)
**Auth:** Internal (`X-Internal-Token`, role `SERVICE`)

**Steps:**
1. Look up `StockItem` by `productId` (read-only).
2. If `StockItem` does not exist → `404 Not Found`.
3. If any of `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm` is null or ≤ 0 → `422 Unprocessable Entity { reason: "packaging_data_missing" }`.
4. All checks pass → `204 No Content`.

> EP-INV-04 and EP-INV-05 share one check, so the two endpoints always give the same answer for the same item. Catalog maps Inventory's `404` to its own activation failure reason `stock_item_not_found`, and passes the `422` `reason` through.

**Edge cases:**
- Non-UUID `productId` → `400`
- Never creates a `StockItem`, never emits an event

**External calls:** none
**Emits:** none
**Refs:** SCN-CM02 (step 2), N-03

---

## EP-INV-05: Get Packaging Dimensions (Internal — Used at TTN Generation)

**Type:** `HTTP GET /inventory/stock/{productId}/packaging`
**Caller:** Delivery Service (internal, called during automatic TTN generation)
**Auth:** Internal (`X-Internal-Token`, role `SERVICE`)

**Steps:**
1. Look up `StockItem` by `productId` (read-only).
2. If not found → `404 Not Found`.
3. If packaging is null or incomplete → `422 Unprocessable Entity { reason: "packaging_data_missing" }`. This causes the Delivery Service to flag the delivery as `TTN_FAILED`.
4. Return `200 OK { grossWeightGrams, lengthCm, widthCm, heightCm }`.

**Edge cases:**
- Non-UUID `productId` → `400`
- `GET` and `PUT` on this path are different endpoints with different roles (internal vs. worker)

**External calls:** none
**Emits:** none
**Refs:** SCN-S04 (step 3), inventory-worker-technical-insights §2, N-08

---

## EP-INV-06: Reserve Stock (Internal — Used at Order Submission)

**Type:** `HTTP POST /inventory/reservations`
**Caller:** Order Service (internal, called during EP-ORD-02 hard reservation)
**Auth:** Internal (`X-Internal-Token`, role `SERVICE`)

**Request:** `{ orderId, items: [ { productId, quantity } ] }`

**Validation (`400`):** `orderId` is a UUID; `items` is non-empty and has at most **100** entries; every item is non-null with a UUID `productId` and `quantity ≥ 1`; a `productId` may appear **only once** (duplicates are rejected, not merged).

**Steps — all in one database transaction:**
1. Lock the `StockItem` rows of all requested products, sorted by `productId`.
2. If the order already has `ACTIVE` reservations → `409 Conflict`. This check runs **after** the locks (see the note below).
3. Find **all** short items before changing anything: an item is short if `availableQuantity < quantity`; a product without a `StockItem` counts as `0` available. Any short item → `422 { insufficientItems: [ { productId, requested, available } ] }` listing every short item; nothing changes.
4. For each item: `availableQuantity -= quantity`, `reservedQuantity += quantity`; create `StockItemReservation(orderId, productId, quantity, status=ACTIVE)`.
5. For each product whose `availableQuantity` crossed `> 0 → 0`, write `ProductStockDepleted` to the outbox in the same transaction.
6. Return `204 No Content`.

> A reservation has no expiry here. The payment-window TTL (`reservationExpiresAt`) is owned by Order Service, which releases the reservation through EP-INV-07 when the window elapses. An order has one or more `StockItemReservation` records (one per product line), so there is no single `reservationId`; Order Service finds them by `orderId`.

> **Why the `409` check runs after the locks.** An order's item list never changes, so a repeated call for the same order (for example a retry after a timeout) has the same items. It waits on the same row locks, and when it gets them the first call has committed, so its existence check sees the `ACTIVE` rows and answers `409`. Checking before the locks would let both calls pass the check. The partial unique index on `(orderId, productId) WHERE status = 'ACTIVE'` remains as a last-resort data guard; if it ever fired, the result would be a logged `500` (a broken invariant), not a `409`.

> **For Order Service:** a `409` for its own `orderId` means "already reserved" (for example, the first call succeeded but its response was lost). Order should treat it as success.

**Edge cases:**
- Two orders racing for the last unit → exactly one succeeds, the other gets `422`
- Two orders reserving the same products listed in different order → both succeed, no deadlock

**External calls:** none

**Emits:**
- `ProductStockDepleted` → when any item's `availableQuantity` crosses >0→0

**Refs:** SCN-C14 (step 3), SCN-C15, N-06

---

## EP-INV-07: Release Stock Reservation (Internal — Used by Saga / TTL Expiry)

**Type:** `HTTP POST /inventory/reservations/{orderId}/release`
**Caller:** Order Service (internal, called during cancellation saga or TTL expiry flow)
**Auth:** Internal (`X-Internal-Token`, role `SERVICE`)

**Steps — all in one database transaction:**
1. Lock the order's `ACTIVE` `StockItemReservation` rows (`SELECT … FOR UPDATE`).
2. If none → `204 No Content`, nothing changes (idempotent: never reserved, already released, or consumed).
3. Lock the affected `StockItem` rows, sorted by `productId`.
4. For each reservation: `availableQuantity += quantity`, `reservedQuantity -= quantity`, `status = RELEASED`.
5. For each product whose `availableQuantity` crossed `0 → > 0`, write `ProductStockReplenished` to the outbox in the same transaction.
6. Return `204 No Content`.

> **Why the reservation rows are locked first.** A concurrent second release of the same order waits on those row locks. When the first release commits, PostgreSQL re-checks `status = 'ACTIVE'` on the changed rows, so the waiting call finds nothing and does nothing: stock is restored exactly once. Release cannot deadlock with reserve, because reserve never waits on existing reservation rows (a reserve for an order with `ACTIVE` rows stops with `409` before inserting).

**Edge cases:**
- Partial release is impossible — all `ACTIVE` reservations of the order are released together
- `RELEASED` and `CONSUMED` rows are never touched; a `CONSUMED` row of the same order stays consumed
- After a release, the same `orderId` can be reserved again
- Non-UUID `orderId` → `400`

**External calls:** none

**Emits:**
- `ProductStockReplenished` → when any item's `availableQuantity` crosses 0→>0

**Refs:** SCN-S06 (step 3), SCN-S07 (step 1)

---

## EP-INV-EVT-01: Consume `ProductCreated`

**Type:** Event consumer (consumer group `inventory-service`)
**Producer:** Catalog Service (Catalog-owned topic `catalog.product-events`, name from `CATALOG_PRODUCT_EVENTS_TOPIC`); only records with header `eventType: ProductCreated` are consumed, others are acknowledged and skipped
**Trigger:** A new product is created by the Catalog Manager (EP-CAT-01).

**Payload received:** `{ eventId, productId, timestamp }` (read into Inventory's own record; unknown fields are ignored). `eventId` and `productId` are mandatory: without them the event goes to the DLT.

**Steps:**
1. Receive and deserialise the `ProductCreated` event.
2. In one transaction: record `eventId` in `processed_events` (Idempotent Consumer); if it was already recorded → duplicate, no action. Otherwise race-safely create `StockItem(productId, availableQuantity=0, reservedQuantity=0, packaging=null)` if it does not exist (`INSERT … ON CONFLICT DO NOTHING`); if it exists → no action.
3. Acknowledge the message.

> This handler is idempotent: if the event is delivered more than once (at-least-once broker delivery), the duplicate is safely ignored. A `StockItem` may also be created on demand by EP-INV-02/03 if this event was missed or has not arrived yet — both orders end in the same state.

**Edge cases:**
- Duplicate delivery of the same event → idempotent; acknowledge without creating a duplicate
- `productId` already has a `StockItem` (created by a prior stock write) → skip; acknowledge
- Creating a `StockItem` emits no event
- The Catalog topic does not exist yet → the service still starts and stays healthy
- Failure while processing → non-blocking retries via `@RetryableTopic`: `catalog.product-events-inventory-retry-0/1/2` (after 1, 2, 4 s), then `catalog.product-events-inventory-dlt`; an unconvertible payload goes to the DLT at once. Inventory creates these topics (3 partitions), and also `catalog.product-events` if Catalog has not yet
- `processed_events` keys are deleted after 30 days, far longer than any redelivery window
- Offsets are committed after the listener returned (ack mode `BATCH`), i.e. after the transaction committed

**External calls:** none
**Refs:** SCN-S01, SCN-CM01 (step 5), N-04

---

## EP-INV-EVT-02: Consume `OrderShipped`

**Type:** Event consumer (consumer group `inventory-service`)
**Producer:** Order Service (Order-owned topic, provisionally `order.order-events`, name from `ORDER_EVENTS_TOPIC`; align it when Order Service publishes); only records with header `eventType: OrderShipped` are consumed, so other order events on the same topic never consume reservations
**Trigger:** Inventory worker confirms parcel handover to carrier; order status transitions to `SHIPPED`.

**Payload received:** `{ orderId, ttn }` (other fields such as `customerEmail` are ignored)

**Steps:**
1. Receive and deserialise the `OrderShipped` event.
2. Lock the order's `ACTIVE` `StockItemReservation` rows, then the affected `StockItem` rows sorted by `productId` (same order as EP-INV-07).
3. If none found → log a warning and acknowledge (defensive; the reservation may already be consumed or released — do not fail).
4. In the same transaction, for each reservation: `reservedQuantity -= quantity` and `status = CONSUMED`. (`availableQuantity` is **not** changed — it was already decremented at reservation time.)
5. Acknowledge the message.

> This is the **permanent deduction** step. Stock was logically removed from available inventory at reservation time (EP-INV-06). This step moves it from "reserved" to "consumed", completing the physical fulfilment. Because `availableQuantity` does not change, no stock event is emitted.

**Edge cases:**
- No `ACTIVE` reservations found for `orderId` → log warning; acknowledge (idempotent)
- Duplicate event delivery, also concurrent → the second execution finds no `ACTIVE` reservations; idempotent acknowledge
- A `RELEASED` reservation is never consumed
- Idempotency comes from the `ACTIVE` status filter (state-based), so no `processed_events` key is needed
- Failure while processing → blocking retries (4 retries after 1, 2, 4, 8 s, keeping the partition's order), then `inventory.order.order-events.dlt` (owned by Inventory, 1 partition); an unconvertible payload goes to the DLT at once
- The Order topic does not exist yet → the service still starts and stays healthy

**External calls:** none
**Refs:** SCN-INV06 (step 6), inventory-worker-technical-insights §4

---

## Implementation Status

| Part | Status |
|---|---|
| EP-INV-01 … EP-INV-07 | Implemented (tasks 01–14) |
| Catalog existence check in EP-INV-02/03 | Implemented ([task 19](../../tasks/inventory-service/19-catalog-product-existence-client.md)), with one retry ([task 20](../../tasks/inventory-service/20-catalog-call-resilience.md)) |
| EP-INV-EVT-01, EP-INV-EVT-02 | Implemented (tasks 15, 16) |
| Transactional outbox for stock events | Implemented ([task 17](../../tasks/inventory-service/17-transactional-outbox.md)) |
| Compose / Kubernetes deployment | Implemented ([infrastructure task](../../tasks/infrastructure/kafka-inventory-wiring-and-minikube-check.md), [task 18](../../tasks/inventory-service/18-containerization-and-deployment.md)); secrets split into `inventory-db-credentials` and `inventory-secrets`; verified in Compose with 2 replicas. Open: a `NetworkPolicy` (cross-service, Security Model) |
| Order Service integration | Order Service does not publish `OrderShipped` yet; the topic name `order.order-events` is provisional |

---

## Changes from the First Spec Version

| Area | First version | Now | Decided in |
|---|---|---|---|
| EP-INV-01 response | plain array | paging envelope `{ content, page, size, totalElements }` | task 08 |
| EP-INV-01 filters | `lowStock`, `packagingMissing` (TBD) | `packagingConfigured=true\|false`; `page`, `size`, `sortBy` | task 08 |
| EP-INV-02 `reason` | "recommended but TBD" | not supported | task 04 |
| EP-INV-03 success | `200 OK` | `204 No Content` | task 09 |
| EP-INV-03 limits | — | no upper bounds, only `> 0` | task 09 |
| EP-INV-04 success | `200 OK { ready: true }` | `204 No Content` | task 10 |
| EP-INV-04 no `StockItem` | `422 { reason: "stock_item_not_found" }` | `404` | tasks 03, 10 |
| EP-INV-04/05 reason | `packaging_dimensions_missing` | `packaging_data_missing` (the check covers gross weight too) | tasks 03, 10, 11 |
| EP-INV-06 response | `{ reservationId, expiresAt }`, later `200` with no fields | `204 No Content` | tasks 12, 13 |
| EP-INV-06 validation | quantities ≥ 1 | also max 100 items, duplicate `productId` → `400` | task 13 |
| EP-INV-07 success | `200 OK` | `204 No Content` | task 14 |
| Stock events | payload only | `eventType` Kafka header on the shared topic `inventory.stock-events` | task 06 |
| Consumed events | — | filtered by the `eventType` header (the source topics are per aggregate and carry other types); dead-letter topics `inventory.<source topic>.dlt` owned by Inventory | tasks 15, 16 |
| `StockItemReservation` | 5 fields | adds `createdAt`, `updatedAt` | task 12 |
| `reservedQuantity` | stored or computed | stored, updated with `availableQuantity` in one transaction | task 02 |
| `ProductCreated` handling | idempotent insert only | `eventId` mandatory; Idempotent Consumer (`processed_events`); non-blocking retry topics + DLT | task 15 (rework for Catalog task 23) |
| Stock event delivery | not specified, then `KafkaTemplate` after commit (task 06) | transactional outbox, at-least-once, per-product order across replicas; extra `eventId` header | task 17 |
| EP-INV-02/03 existence check | `GET /catalog/products/{productId}` | internal `GET /catalog/products/batch-get?ids=`, called outside the locking transaction | task 19 |
| EP-INV-02/03 Catalog failure | not specified | `503` with fixed message and `Retry-After: 10`; one retry for transient failures, no circuit breaker | tasks 19, 20 |
