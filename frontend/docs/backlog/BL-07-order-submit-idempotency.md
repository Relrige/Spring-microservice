---
id: BL-07
title: Idempotent order submission
status: Done
importance: Important
services: [order-service]
blocks: —
created: 2026-10-20
---

# BL-07: Idempotent order submission

## Problem

If the customer's "Submit order" request is sent twice (double click, a retry after a lost response, two tabs), two orders are created, each reserving stock. The unpaid one holds stock until it expires after the payment window.

## Current behaviour

*As of 2026-10-20, before the backend work; see* Delivered *below.*

[Order spec EP-ORD-02](../../../docs/design/services-specs/order-service-spec.md#ep-ord-02-submit-order--hard-stock-reservation), edge cases: "Double submission (double click) creates two orders, each with its own reservation; the unpaid one expires. Accepted for now (an `Idempotency-Key` header would prevent it)." Payment's idempotency covers charges only, not order creation.

## Proposed change

`POST /orders` accepts an `Idempotency-Key` header (client-generated UUID, one per checkout attempt):
- First request with a key: processed normally; the key, the customer and a hash of the request body are stored with the result.
- Repeat with the same key and the same body from the same customer: the stored result is returned (`201` with the same `orderId`), nothing is reserved again.
- Same key with a different body: `422` (or `409`), nothing created.
- A repeat while the first is still running: `409` "request in progress".
- Keys expire after e.g. 24 hours.
The gateway already forwards headers other than the identity headers, so no gateway change is expected.

## Delivered (2026-10-22)

Implemented in `../../../docs/tasks/order-service/19-idempotent-order-submission.md` and `20-purge-idempotency-keys-job.md` (Order spec EP-ORD-02 *Idempotency-Key*, EP-ORD-JOB-05, decisions D4–D10), on PR [Relrige/Spring-microservice#23](https://github.com/Relrige/Spring-microservice/pull/23) (not merged yet). Verified through the gateway on Compose. What the frontend needs to know, including where it differs from the request:

- **Header:** `Idempotency-Key`, optional, must be a **UUID** (anything else → `400` type `validation`, without `errors`). Scoped per customer.
- **Replay:** `201` with the same `orderId` and `Location`, plus the response header **`Idempotent-Replayed: true`**. The body is built from the order's **current** state, not stored: `status` may already be `PAID` or later. `reservationExpiresAt` of a replay has microsecond precision, so compare instants, not strings.
- **Same key, other body** (another cart or other delivery details): `422` type **`idempotency-key-mismatch`**, plain `Problem`. It shares the `422` with `ineligible-items`, `insufficient-stock` and `order-total-too-large`; tell them apart with `problemOf`.
- **Repeat while the first still runs:** `409` type **`request-in-progress`** with `Retry-After: 1`; retry with the same key.
- **Only success is remembered:** after a `422`, `503` or `500` the key is free again, so retrying a failed submit with the same key runs it again (a new attempt, a new `orderId` if it succeeds).
- **Expiry:** a completed key is forgotten after 24 hours (`ORDER_IDEMPOTENCY_KEY_TTL`); then the same key places a new order. A claim left by a crashed Order instance answers `409` for up to about 3 minutes, then is purged.
- **Generated types** (`order.ts`, regenerated in the same PR): the header parameter, the `Idempotent-Replayed` response header, the new `409` and `Problem` in the `422` union.

## Requirements

- [x] Repeating `POST /orders` with the same key returns the first order and creates no second order or reservation
- [x] Key reuse with another body is rejected (`422 idempotency-key-mismatch`)
- [x] Concurrent duplicates create one order
- [x] Without the header the behaviour is unchanged
- [x] Updated: order spec EP-ORD-02, `api-contracts.md`
- [x] The checkout page sends one key per attempt and retries with it (frontend side, iteration 3; marks the item `Done`)

## Frontend impact

Done in the OpenSpec change `customer-checkout-orders` (2026-10-22): the delivery step sends one `Idempotency-Key` per attempt, reuses it for a retry, makes a new one when the cart lines or delivery details change or after `422 idempotency-key-mismatch`, and repeats once after `409 request-in-progress`. Verified on the Compose stack: the same request twice with one key answered `201` with the same order (`Idempotent-Replayed: true` on the second), and the key with another body answered `422 idempotency-key-mismatch`.


- Until done: the submit button is disabled while the request runs (iteration 3); a lost response can still leave a second order.
- After: the checkout page sends one key per attempt and can retry a failed submit safely.
  - Generate the key (`crypto.randomUUID()`) when the customer starts a submit, and keep it while the cart and delivery details stay the same: a retry after a lost response, a `503` or a `409 request-in-progress` (after `Retry-After`) reuses it.
  - Make a new key when the cart or the delivery details change; reusing the old one would answer `422 idempotency-key-mismatch`.
  - Treat `201` with or without `Idempotent-Replayed` the same: go to the order. The mock handler for `POST /orders` should model the replay, the `409` and the `422` mismatch.
