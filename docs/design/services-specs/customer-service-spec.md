# Customer Service — Implementation Spec

Responsible for customer profile data and the backend cart aggregate. Separated from Auth Service to keep identity concerns lean and allow customer profile data to evolve independently. References `customerId` (issued by Auth Service) as a foreign key but owns no credentials.

`CustomerProfile` and `Cart` are treated as **two distinct aggregates** inside this service, sharing the same `customerId` key but with separate lifecycles — `Cart` can exist without a `CustomerProfile` (e.g., a user who has never saved their profile).

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `CustomerProfile` (`customerId`, `firstName`, `lastName`, `phone`, `defaultAddress`, `createdAt`, `updatedAt`); `Cart` (`customerId`, `createdAt`, `updatedAt`) → `CartItem` (`cartId`, `productId`, `quantity`) |
| **Events emitted** | none |
| **Events consumed** | `OrderPaid` (from Order Service) |
| **Port** | container `8080`; Compose host port and Kubernetes Service port `8087` |

---

## Security and Configuration

**Security.** Every endpoint is called by a logged-in customer through the API Gateway, which validates the JWT and forwards `X-User-Id` and `X-User-Role`. The service trusts these headers (see the [Security Model](../services-requirements/security-model.md)) and takes `customerId` from `X-User-Id` only, never from the path or body, so a customer can reach only their own profile and cart. All nine routes require role `CUSTOMER` (`401` without valid headers, `403` for any other role, including `ADMIN`); every other route is denied. Customer Service has no internal (service-to-service) routes and makes no outgoing calls, so unlike the other services it has no `X-Internal-Token` filter and no `AUTH_INTERNAL_TOKEN`.

**Configuration** (environment variables; see `customer-service/.env.example` and `k8s/customer/app-config.yaml`):

| Variable | Meaning |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | The service's own PostgreSQL database (database per service) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker (`kafka:9092` in Compose and Kubernetes; default `localhost:9094` for the IDE) |
| `ORDER_EVENTS_TOPIC` | Order's topic carrying `OrderPaid` (default `order.order-events`, provisional until Order publishes) |

---

## EP-CUST-01: Get Customer Profile

**Type:** `HTTP GET /customers/me/profile`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from the `X-User-Id` header (set by API Gateway after JWT validation).
2. Query `CustomerProfile` by `customerId`.
3. If a profile record exists → return `200 OK { firstName, lastName, phone, defaultAddress }`.
4. If no profile record exists (lazy creation — user registered but never saved a profile) → return `200 OK` with all fields `null`. This is **not** an error; the client uses it to pre-populate an empty form.

**Edge cases:**
- JWT missing or incorrect role → rejected at API Gateway before reaching this service

**External calls:** none
**Emits:** none
**Refs:** US-CUST-06, SCN-C13

---

## EP-CUST-02: Upsert Customer Profile

**Type:** `HTTP PUT /customers/me/profile`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Validate request body:
   - `firstName`, `lastName`: required, non-blank, at most 100 characters.
   - `phone`: required, E.164 format `^\+[1-9]\d{7,14}$` (a plus, then 8–15 digits, no spaces), e.g. `+380501234567`.
   - `defaultAddress`: optional free text, at most 500 characters.
3. Upsert `CustomerProfile` for `customerId` in **one atomic statement** (`INSERT … ON CONFLICT (customer_id) DO UPDATE`): insert if not exists, update if exists. `createdAt` is kept on update; `updatedAt = now()`. Two parallel first saves therefore end as one row (the later one wins) instead of a duplicate-key error.
4. Return `200 OK { firstName, lastName, phone, defaultAddress }` as stored.

**Edge cases:**
- `firstName` or `lastName` is blank or too long → `400 Bad Request`
- `phone` missing or not in E.164 format → `400 Bad Request`
- `defaultAddress` too long → `400 Bad Request`
- Field errors are listed in the `errors` property of the `400` Problem Details response

**External calls:** none
**Emits:** none
**Refs:** US-CUST-06, SCN-C25

---

## Cart Aggregate: Model, Invariants & Concurrency

This section applies to every cart endpoint (EP-CUST-03 … EP-CUST-09) and to the `OrderPaid` consumer (EP-CUST-EVT-01).

### Persistence model

`Cart` is the aggregate root; `CartItem` is only created, changed, or removed through it.

```
carts      (customer_id UUID PRIMARY KEY,
            created_at  TIMESTAMPTZ NOT NULL,
            updated_at  TIMESTAMPTZ NOT NULL)

cart_items (cart_id    UUID NOT NULL REFERENCES carts (customer_id),
            product_id UUID NOT NULL,
            quantity   INT  NOT NULL CHECK (quantity BETWEEN 1 AND 10),
            PRIMARY KEY (cart_id, product_id))
```

- A `carts` row is keyed by `customerId` (issued by Auth Service); a customer has at most one cart.
- `cart_items` references the cart, not the customer directly.
- There is no `version` column: concurrent writes are serialised by a pessimistic lock (see below), so optimistic locking is not needed.
- Clearing a cart deletes its items but **keeps the `carts` row** — an empty cart is still a cart.
- Batch changes (replace, merge) are applied to the stored lines as a difference — update the lines that stay, delete the lines that go, insert the new ones — never as "delete all, then insert all", which would re-insert a still-present `(cart_id, product_id)` key within one flush.
- Every cart response is the whole cart after the operation, as a JSON array of `{ productId, quantity }` **ordered by `productId`**.

### Invariants

| ID | Rule |
|---|---|
| INV-CART-01 | A cart contains at most **10 distinct products** (10 `CartItem` lines). |
| INV-CART-02 | Each `CartItem.quantity` is between **1 and 10** inclusive. |

- Invariants are checked against the **result** of an operation (e.g. the quantity after an increment or after a merge sum), not only against the request values.
- An operation that would violate an invariant is rejected as a whole; the cart is left unchanged (all-or-nothing, one transaction).
- Invariants are enforced in the `Cart` aggregate root's domain methods. The database `CHECK` and primary key are a safety net only.

### Error conventions

| Status | When |
|---|---|
| `400 Bad Request` | Malformed request: invalid JSON, missing or null fields (including a `null` element of `items`), wrong types (e.g. a decimal `quantity`), `productId` in the body or path not a valid UUID, `quantity` < 1. The Problem Details body lists field errors in `errors` (e.g. `errors["items[0].quantity"]`). |
| `422 Unprocessable Entity` | Well-formed request that breaks a cart rule: an invariant violation or a duplicate `productId` within one batch request. |
| `404 Not Found` | Only for EP-CUST-05, when the targeted item is not in the cart. |

The `422` body is a Problem Details response with a `violation` code and its details:

| `violation` | Extra fields |
|---|---|
| `TOO_MANY_PRODUCTS` | `resultingProductCount`, `maxProducts` (10) |
| `QUANTITY_LIMIT_EXCEEDED` | `productIds` — the products whose resulting quantity would exceed the limit; `maxQuantity` (10) |
| `DUPLICATE_PRODUCT` | `productIds` — the products that appear more than once in the request |

### Concurrency

The same customer may change their cart from several devices at once. All cart-changing operations are serialised per cart with a **pessimistic row lock** on `carts`:

1. **Create if missing** (only for operations that may add items — EP-CUST-04, EP-CUST-07, EP-CUST-08): `INSERT INTO carts (customer_id, created_at, updated_at) VALUES (?, now(), now()) ON CONFLICT (customer_id) DO NOTHING` (native query). After this step a row always exists, so step 2 always has something to lock.
2. **Lock:** `SELECT … FROM carts WHERE customer_id = ? FOR UPDATE` (JPA `PESSIMISTIC_WRITE`). Operations that only modify or remove existing items (EP-CUST-05, EP-CUST-06, EP-CUST-09, EP-CUST-EVT-01) skip step 1; if no cart row is found, the cart is treated as empty.
3. **Load, change, check:** load the items and apply the change through the aggregate root, which checks the invariants.
4. **Save:** set `Cart.updatedAt = now()`; commit releases the lock.

Steps 1–4 run in one transaction. Read-only operations (EP-CUST-03, and EP-CUST-08 with an empty `items` array) neither create a cart nor take a lock. A rule that depends only on the request (a duplicate `productId`) is checked before the lock is taken, so a bad request does not wait for it.

Because writes are serialised by the lock, item changes use a plain read-then-insert-or-update; no `ON CONFLICT` upsert is needed for `cart_items`.

### Client usage

The cart service only persists the cart; it does not drive checkout. The client keeps a local copy of the server cart to render the view, and sends the matching request to Customer Service on every change. At checkout the client sends the cart items to Order Service itself — Order Service never reads the cart from Customer Service.

**Guest cart on login.** A guest cart lives only on the client (e.g. `localStorage`). When a user logs in and both the local guest cart and the server cart are non-empty, the client asks the user what to do:

| User choice | Client action |
|---|---|
| Replace the account cart with the guest cart | `PUT /customers/cart` (EP-CUST-07) |
| Keep the account cart, discard the guest cart | `GET /customers/cart` (EP-CUST-03) |
| Merge the guest cart into the account cart | `POST /customers/cart/merge` (EP-CUST-08) |

If either cart is empty, the client does not ask: an empty guest cart means keep the account cart; an empty account cart means replace it with the guest cart. In every case the client discards the local guest cart afterwards and works with the server cart only.

---

## EP-CUST-03: Get Cart

**Type:** `HTTP GET /customers/cart`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Query all `CartItem` records of the cart of `customerId`. No cart is created and no lock is taken.
3. Return `200 OK [ { productId, quantity } ]`, ordered by `productId`.

> Cart stores only `(productId, quantity)` pairs. Product names, prices, and availability are **not** stored or returned here. The client fetches live product data from Catalog Service for each `productId` when rendering the cart.

**Edge cases:**
- No cart row exists, or the cart is empty → return `200 OK []` (empty array, not a 404)

**External calls:** none
**Emits:** none
**Refs:** US-CUST-03, US-CUST-04, US-CUST-05

---

## EP-CUST-04: Add Item to Cart

**Type:** `HTTP POST /customers/cart/items`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Validate request body: `productId` is a non-null, valid UUID; `quantity` is non-null and ≥ 1.
3. Create the cart if missing, then lock it (see *Concurrency*).
4. Check whether a `CartItem` with this `productId` already exists in the cart.
   - If yes: increment the existing `quantity` by the requested amount. If the result exceeds 10 → `422 QUANTITY_LIMIT_EXCEEDED`.
   - If no: if the cart already has 10 items → `422 TOO_MANY_PRODUCTS`; if `quantity` exceeds 10 → `422 QUANTITY_LIMIT_EXCEEDED`; otherwise insert a new `CartItem(cartId, productId, quantity)`.
5. Return `200 OK` with the full updated cart item list.

> Customer Service does **not** validate whether the product is `ACTIVE` or in stock at add-to-cart time. That validation is deliberately deferred to the checkout soft-check (EP-ORD-01) to avoid coupling on every cart action.

**Edge cases:**
- `productId` null or not a valid UUID → `400 Bad Request`
- `quantity` null or < 1 → `400 Bad Request`
- Resulting quantity > 10 → `422 Unprocessable Entity` (`QUANTITY_LIMIT_EXCEEDED`)
- New product while the cart already holds 10 products → `422 Unprocessable Entity` (`TOO_MANY_PRODUCTS`)

**External calls:** none
**Emits:** none
**Refs:** US-CUST-03, INV-CART-01, INV-CART-02

---

## EP-CUST-05: Update Cart Item Quantity

**Type:** `HTTP PATCH /customers/cart/items/{productId}`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Validate request body: `quantity` is non-null and ≥ 1.
3. Lock the cart (no cart is created).
4. Find the `CartItem` with `productId` in the cart. If there is no cart or no such item → `404`.
5. If `quantity` exceeds 10 → `422 QUANTITY_LIMIT_EXCEEDED`.
6. Set `CartItem.quantity` to the new explicit value (this is a **set**, not an increment).
7. Return `200 OK` with the updated cart.

**Edge cases:**
- `productId` in path has no corresponding item in this customer's cart → `404 Not Found`
- `quantity` null or < 1 → `400 Bad Request`
- `quantity` > 10 → `422 Unprocessable Entity` (`QUANTITY_LIMIT_EXCEEDED`)

**External calls:** none
**Emits:** none
**Refs:** US-CUST-04, INV-CART-02

---

## EP-CUST-06: Remove Item from Cart

**Type:** `HTTP DELETE /customers/cart/items/{productId}`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Lock the cart (no cart is created).
3. Delete the `CartItem` with `productId` from the cart.
4. If the item did not exist, treat as a no-op (idempotent behaviour — client may call this multiple times safely).
5. Return `200 OK` with the updated cart.

**Edge cases:**
- Item not in cart, or no cart row exists → silently succeed (idempotent delete)

**External calls:** none
**Emits:** none
**Refs:** US-CUST-04

---

## EP-CUST-07: Replace Cart

**Type:** `HTTP PUT /customers/cart`
**Caller:** Customer (via API Gateway). Used after login when the user chooses to replace the account cart with the guest cart.
**Auth:** `ROLE_CUSTOMER`

**Request:** `{ items: [ { productId, quantity } ] }`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Validate request body: `items` is a non-null array (may be empty); every item has a valid UUID `productId` and a non-null `quantity` ≥ 1.
3. If the same `productId` appears more than once → `422 DUPLICATE_PRODUCT` (checked before the lock).
4. Create the cart if missing, then lock it.
5. Replace the lines through the aggregate, which checks the invariants first:
   - More than 10 items → `422 TOO_MANY_PRODUCTS`.
   - Any `quantity` exceeds 10 → `422 QUANTITY_LIMIT_EXCEEDED` (`productIds` lists every offending product).
   - Otherwise the cart becomes exactly the submitted items (applied as a difference, see *Persistence model*).
6. Return `200 OK` with the new cart.

**Idempotency:** repeating the same request gives the same result — safe for the client to retry.

**Edge cases:**
- Empty `items` array → the cart ends up empty (same effect as EP-CUST-09)
- Any item malformed → `400 Bad Request`; reject the whole request
- Any rule broken → `422 Unprocessable Entity`; the existing cart is left unchanged

**External calls:** none
**Emits:** none
**Refs:** US-CUST-05, INV-CART-01, INV-CART-02

---

## EP-CUST-08: Merge Items into Cart

**Type:** `HTTP POST /customers/cart/merge`
**Caller:** Customer (via API Gateway). Used after login when the user chooses to merge the guest cart into the account cart.
**Auth:** `ROLE_CUSTOMER`

**Request:** `{ items: [ { productId, quantity } ] }`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Validate request body: `items` is a non-null array (may be empty); every item has a valid UUID `productId` and a non-null `quantity` ≥ 1.
3. If the same `productId` appears more than once → `422 DUPLICATE_PRODUCT`.
4. If `items` is empty → no-op; return the current cart as-is (no cart is created, no lock is taken).
5. Create the cart if missing, then lock it.
6. Compute the merged cart:
   - Product already in the cart → new quantity = existing quantity + submitted quantity.
   - Product not in the cart → new item with the submitted quantity.
7. Check the invariants on the merged result:
   - More than 10 distinct products → `422 TOO_MANY_PRODUCTS` (`resultingProductCount` is the merged count).
   - Any resulting quantity exceeds 10 → `422 QUANTITY_LIMIT_EXCEEDED` (`productIds` lists every offending product).
8. Save the merged items.
9. Return `200 OK` with the merged cart.

**Known limitation — not idempotent:** if the server commits but the response is lost and the client retries, quantities are summed twice. This is accepted for this project; the invariants still cap the result.

**Edge cases:**
- Empty `items` array → no-op; return current backend cart as-is
- Any item malformed → `400 Bad Request`; reject the whole request
- Any rule broken → `422 Unprocessable Entity`; the existing cart is left unchanged

**External calls:** none
**Emits:** none
**Refs:** US-CUST-05, INV-CART-01, INV-CART-02

---

## EP-CUST-09: Clear Cart

**Type:** `HTTP DELETE /customers/cart`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Lock the cart (no cart is created).
3. Delete all `CartItem` records of the cart. The `carts` row is kept.
4. Return `200 OK []`.

**Edge cases:**
- Cart already empty, or no cart row exists → no-op; return `200 OK []` (idempotent)

**External calls:** none
**Emits:** none
**Refs:** US-CUST-04

---

## EP-CUST-EVT-01: Consume `OrderPaid`

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** Customer successfully completes card payment; order status transitions to `PAID`.

**Topic:** `ORDER_EVENTS_TOPIC` (default `order.order-events`, owned and declared by Order), consumer group `customer-service`. Order's events share the topic and are told apart by the `eventType` header; one listener handles `OrderPaid` and acknowledges and skips every other type (or a missing header).

**Payload received:** the fat `OrderPaid` from the [Event Catalogue](../services-requirements/events-catalogue.md). Customer reads only `{ eventId, orderId, customerId (nullable) }` and ignores the other fields (tolerant reader). `orderId` is required.

**Steps:**
1. Receive and deserialise the `OrderPaid` event.
2. If `customerId` is null → skip and acknowledge. Since 2026-10-16 only registered customers can order, so Order always sends a `customerId`; the check stays as a defensive guard (it was the guest-order case before).
3. If `customerId` is present → clear the cart exactly as EP-CUST-09 does: lock the cart, delete all its `CartItem` records, keep the `carts` row.
4. Acknowledge the message: offsets are committed only after the clearing transaction committed (ack mode `batch`).

**Idempotency:** by state — clearing an empty or missing cart changes nothing, so a redelivered event is harmless and no processed-events table is needed.

**Failures:**
- A payload that is not JSON, has no `orderId`, or has a non-UUID ID is a poison message: no retries, sent at once to the dead-letter topic `customer.order.order-events.dlt` (owned and declared by Customer, 1 partition).
- Other failures (e.g. the database briefly unavailable) are retried 4 times with back-off (1 s, 2 s, 4 s, 8 s), then sent to the same dead-letter topic, so one bad record never blocks the partition.
- Order's topic may not exist yet: the listener waits for it and the service still starts (`missing-topics-fatal: false`).

**Edge cases:**
- `customerId` present but no cart row or no cart items → no-op; no cart row is created; acknowledge (idempotent)
- `customerId` present but no `CustomerProfile` record exists → the cart may still exist; proceed with clearing; acknowledge successfully (profile and cart are independent aggregates)
- Items added after paying but before the event is processed are cleared too. Accepted: the event follows the payment closely.

**External calls:** none
**Refs:** SCN-C16 (step 9), N-05

---

## Implementation Status

| Part | Status |
|---|---|
| EP-CUST-01…09, EP-CUST-EVT-01 | Implemented ([tasks 01–08](../../tasks/customer-service/)); covered by unit, WebMvc and Testcontainers (PostgreSQL + Kafka) tests, including concurrency tests of the cart lock and of the first profile save |
| Container image | Built and smoke-tested against PostgreSQL 18 and Kafka 4.1 (all endpoints, each `422` violation, `OrderPaid` clearing a cart, a poison record on the DLT) ([task 09](../../tasks/customer-service/09-containerization-and-deployment.md)) |
| Compose | Entries added (`customer-service`, `customer-service-postgres`, host port 8087); configuration validated, not started as a whole stack |
| Kubernetes | Manifests in `k8s/customer/` and a database NetworkPolicy; not yet deployed to minikube; scaling to 2 replicas not tried |
| Gateway routes | Not yet routed ([API gateway task 10](../../tasks/api-getaway/10-remaining-service-routes.md), Customer part now unblocked) |
| `OrderPaid` end to end | Waits for Order Service to publish `OrderPaid` with `customerId` |

---

## Changes from the First Spec Version

| Area | First version | Now | Decided in |
|---|---|---|---|
| Guest cart on login | one `POST /customers/cart/merge`; conflicts "a client-side concern", "keep the higher quantity" as a default | the user chooses: replace (`PUT /customers/cart`), keep (`GET`), or merge with summed quantities (`POST /customers/cart/merge`); plus `DELETE /customers/cart` to clear | design review 2026-10-15 |
| Cart limits | none | at most 10 products per cart, quantity 1–10 per product, checked on the result of every operation; `422` with a `violation` code | design review 2026-10-15 |
| Cart persistence | `CartItem (customerId, productId, quantity)` | `carts` row as aggregate root, `cart_items` referencing it; the row is created on the first adding operation and kept when the cart is cleared | design review 2026-10-15 |
| Concurrency | not specified | pessimistic lock on the `carts` row (create if missing, then `SELECT … FOR UPDATE`) | design review 2026-10-15 |
| Phone format | "exact regex TBD" | E.164, required; `defaultAddress` optional; length limits | implementation 2026-10-16 ([task 04](../../tasks/customer-service/04-customer-profile.md)) |
| Profile upsert | insert or update | one atomic `INSERT … ON CONFLICT DO UPDATE`, safe for parallel first saves | implementation 2026-10-16 ([task 04](../../tasks/customer-service/04-customer-profile.md)) |
| Internal token | — | none: the service has no internal routes and makes no outgoing calls | implementation 2026-10-16 ([task 03](../../tasks/customer-service/03-trusted-header-security.md)) |
