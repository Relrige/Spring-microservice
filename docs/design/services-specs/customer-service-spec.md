# Customer Service — Implementation Spec

Responsible for customer profile data and the backend cart aggregate. Separated from Auth Service to keep identity concerns lean and allow customer profile data to evolve independently. References `customerId` (issued by Auth Service) as a foreign key but owns no credentials.

`CustomerProfile` and `Cart` are treated as **two distinct aggregates** inside this service, sharing the same `customerId` key but with separate lifecycles — `Cart` can exist without a `CustomerProfile` (e.g., a user who has never saved their profile).

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `CustomerProfile` (`customerId`, `firstName`, `lastName`, `phone`, `defaultAddress`, `createdAt`, `updatedAt`); `Cart` → `CartItem` (`customerId`, `productId`, `quantity`) |
| **Events emitted** | none |
| **Events consumed** | `OrderPaid` (from Order Service) |

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
2. Validate request body: `firstName` and `lastName` non-empty; `phone` in a valid format (exact regex TBD during implementation).
3. Upsert `CustomerProfile` for `customerId` — insert if not exists, update if exists. Set `updatedAt = now()`.
4. Return `200 OK { firstName, lastName, phone, defaultAddress }`.

**Edge cases:**
- `firstName` or `lastName` is blank → `400 Bad Request`
- `phone` format invalid → `400 Bad Request`

**External calls:** none
**Emits:** none
**Refs:** US-CUST-06, SCN-C25

---

## EP-CUST-03: Get Cart

**Type:** `HTTP GET /customers/cart`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Query all `CartItem` records for `customerId`.
3. Return `200 OK [ { productId, quantity } ]`.

> Cart stores only `(productId, quantity)` pairs. Product names, prices, and availability are **not** stored or returned here. The client fetches live product data from Catalog Service for each `productId` when rendering the cart.

**Edge cases:**
- Cart is empty → return `200 OK []` (empty array, not a 404)

**External calls:** none
**Emits:** none
**Refs:** US-CUST-03, US-CUST-04, US-CUST-05, SCN-C08

---

## EP-CUST-04: Add Item to Cart

**Type:** `HTTP POST /customers/cart/items`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Validate request body: `productId` is a non-null, valid UUID; `quantity` ≥ 1.
3. Check whether a `CartItem` with this `productId` already exists for `customerId`.
   - If yes: increment the existing `quantity` by the requested amount.
   - If no: insert a new `CartItem(customerId, productId, quantity)`.
4. Return `200 OK` with the full updated cart item list.

> Cart Service does **not** validate whether the product is `ACTIVE` or in stock at add-to-cart time. That validation is deliberately deferred to the checkout soft-check (EP-ORD-01) to avoid coupling on every cart action.

**Edge cases:**
- `productId` null or not a valid UUID → `400 Bad Request`
- `quantity` < 1 → `400 Bad Request`

**External calls:** none
**Emits:** none
**Refs:** US-CUST-03, SCN-C07

---

## EP-CUST-05: Update Cart Item Quantity

**Type:** `HTTP PATCH /customers/cart/items/{productId}`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Validate request body: `quantity` ≥ 1.
3. Find `CartItem` with `(customerId, productId)`. If not found → `404`.
4. Set `CartItem.quantity` to the new explicit value (this is a **set**, not an increment).
5. Return `200 OK` with the updated cart.

**Edge cases:**
- `productId` in path has no corresponding item in this customer's cart → `404 Not Found`
- `quantity` < 1 → `400 Bad Request`

**External calls:** none
**Emits:** none
**Refs:** US-CUST-04, SCN-C09

---

## EP-CUST-06: Remove Item from Cart

**Type:** `HTTP DELETE /customers/cart/items/{productId}`
**Caller:** Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Delete `CartItem` with `(customerId, productId)`.
3. If the item did not exist, treat as a no-op (idempotent behaviour — client may call this multiple times safely).
4. Return `200 OK` with the updated cart.

**Edge cases:**
- Item not in cart → silently succeed (idempotent delete)

**External calls:** none
**Emits:** none
**Refs:** US-CUST-04, SCN-C09

---

## EP-CUST-07: Merge Guest Cart on Login

**Type:** `HTTP POST /customers/cart/merge`
**Caller:** Customer (via API Gateway, called by the client immediately after a successful login)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Validate request body: `items` is an array of `{ productId, quantity }` pairs (may be empty).
3. For each item in the submitted guest cart:
   - If a `CartItem` with the same `productId` already exists for `customerId`: conflict resolution is a **client-side concern** (deferred, N-12). As a safe default, the service may keep the higher of the two quantities — exact merge strategy TBD during implementation.
   - If no existing `CartItem` for this `productId`: insert `CartItem(customerId, productId, quantity)`.
4. Return `200 OK` with the merged cart.

**Edge cases:**
- Empty `items` array → no-op; return current backend cart as-is
- Any item has `quantity` < 1 → `400 Bad Request`; reject the whole request body

**External calls:** none
**Emits:** none
**Refs:** US-CUST-05, SCN-C10, N-05, N-12

---

## EP-CUST-EVT-01: Consume `OrderPaid`

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** Customer successfully completes card payment; order status transitions to `PAID`.

**Payload received:** `{ orderId, customerId (nullable), customerEmail }`

**Steps:**
1. Receive and deserialise the `OrderPaid` event.
2. If `customerId` is null → this is a guest order; no backend cart exists. Skip and acknowledge.
3. If `customerId` is present → delete all `CartItem` records for `customerId`.
4. Acknowledge the message.

**Edge cases:**
- `customerId` present but no cart items found → no-op; acknowledge (idempotent)
- `customerId` present but no `CustomerProfile` record exists → cart items may still exist; proceed with deletion; acknowledge successfully (profile and cart are independent aggregates)

**External calls:** none
**Refs:** SCN-C16 (step 9), N-05
