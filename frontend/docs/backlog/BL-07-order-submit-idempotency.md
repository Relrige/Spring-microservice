---
id: BL-07
title: Idempotent order submission
status: Open
importance: Important
services: [order-service]
blocks: —
created: 2026-10-20
---

# BL-07: Idempotent order submission

## Problem

If the customer's "Submit order" request is sent twice (double click, a retry after a lost response, two tabs), two orders are created, each reserving stock. The unpaid one holds stock until it expires after the payment window.

## Current behaviour

[Order spec EP-ORD-02](../../../docs/design/services-specs/order-service-spec.md#ep-ord-02-submit-order--hard-stock-reservation), edge cases: "Double submission (double click) creates two orders, each with its own reservation; the unpaid one expires. Accepted for now (an `Idempotency-Key` header would prevent it)." Payment's idempotency covers charges only, not order creation.

## Proposed change

`POST /orders` accepts an `Idempotency-Key` header (client-generated UUID, one per checkout attempt):
- First request with a key: processed normally; the key, the customer and a hash of the request body are stored with the result.
- Repeat with the same key and the same body from the same customer: the stored result is returned (`201` with the same `orderId`), nothing is reserved again.
- Same key with a different body: `422` (or `409`), nothing created.
- A repeat while the first is still running: `409` "request in progress".
- Keys expire after e.g. 24 hours.
The gateway already forwards headers other than the identity headers, so no gateway change is expected.

## Requirements

- [ ] Repeating `POST /orders` with the same key returns the first order and creates no second order or reservation
- [ ] Key reuse with another body is rejected
- [ ] Concurrent duplicates create one order
- [ ] Without the header the behaviour is unchanged
- [ ] Updated: order spec EP-ORD-02, `api-contracts.md`

## Frontend impact

- Until done: the submit button is disabled while the request runs (iteration 3); a lost response can still leave a second order.
- After: the checkout page sends one key per attempt and can retry a failed submit safely.
