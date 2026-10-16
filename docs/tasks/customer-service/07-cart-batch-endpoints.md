---
status: DONE
service: customer-service
---
# Batch Cart Endpoints: Replace, Merge, Clear (EP-CUST-07 … EP-CUST-09)

## Context
When a user with a local guest cart logs in, the client lets them choose: replace the account cart with the guest cart, keep the account cart, or merge the two. Keeping needs only `GET`. Replacing and merging are single batch requests, so the change is all-or-nothing. Clearing deletes every item but keeps the cart; the `OrderPaid` consumer ([task 08](08-consume-order-paid.md)) reuses the same operation.
*References:* [Customer Spec — Client usage, EP-CUST-07 to EP-CUST-09](../../design/services-specs/customer-service-spec.md), [API Contracts — Customer Service](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] `PUT /customers/cart` `{items: [{productId, quantity}]}` → create if missing, lock, `Cart.replaceAll`; `200 OK` with the new cart. Repeating the request gives the same result.
- [x] `POST /customers/cart/merge` `{items}` → empty `items` returns the current cart without creating or locking; otherwise create if missing, lock, `Cart.mergeFrom`; `200 OK` with the merged cart.
- [x] `DELETE /customers/cart` → lock if exists (no creation), `Cart.clear`; the `carts` row stays; idempotent; `200 OK []`.
- [x] Validation: `items` non-null (may be empty); each item has a valid UUID `productId` and `quantity` ≥ 1; otherwise `400` for the whole request.
- [x] Rules: a duplicate `productId` → `422 DUPLICATE_PRODUCT`; more than 10 products in the result → `422 TOO_MANY_PRODUCTS`; any resulting quantity > 10 → `422 QUANTITY_LIMIT_EXCEEDED` with every offending product. A rejected request leaves the cart unchanged.
- [x] Tests (integration):
  - replace: on an empty cart, on a full cart, the same request twice, with `[]`, with 11 items, with a duplicate
  - merge: disjoint carts, overlapping products summed, a sum of exactly 10, a sum over 10 with two offending products (both listed), a result with 11 products, `[]` with no cart (no row created), a duplicate
  - clear: a cart with items (the row stays), no cart (no row created), twice

## Technical Notes / Constraints
- Merge is **not idempotent**: a retry after a lost response sums twice. This is an accepted limitation in the spec; do not add an idempotency key.
- Check duplicates **before** taking the lock: it depends only on the request, so a bad request does not have to wait for the lock.
- Map the request to the domain's item type in the controller or service; do not pass DTOs into `Cart`.

## Implementation Notes (2026-10-16)
- **`CartController`:** `PUT /customers/cart` (replace), `POST /customers/cart/merge`, `DELETE /customers/cart` (clear). Each returns the whole cart after the operation (`[]` for clear).
- **`CartItemsRequest`** (`{items: [{productId, quantity}]}`) is shared by replace and merge: `items` `@NotNull` (may be empty), elements `@NotNull @Valid` with `productId` `@NotNull` and `quantity` `@NotNull @Min(1)`. A broken element is reported as `errors["items[0].quantity"]`. `toLines()` maps the DTO to domain `CartLine`s in the controller, so the aggregate never sees a DTO.
- **`CartService`:**
  - `replaceCart`: duplicate check (`CartLine.requireNoDuplicates`) **before** the lock, then create if missing + `Cart.replaceAll`.
  - `mergeIntoCart`: duplicate check before the lock; empty `items` → `getCart` (no creation, no lock); otherwise create if missing + `Cart.mergeFrom`.
  - `clearCart`: lock if exists + `Cart.clear` (row kept; no cart → nothing created). It is the operation the `OrderPaid` consumer reuses (task 08).
- **Replace with `[]` on a customer without a cart creates an empty cart row.** That is harmless and keeps the rule simple ("operations that may add items create the cart"), as the spec states for EP-CUST-07.
- **Merge is not idempotent** (accepted in the spec); no idempotency key.
- **Verified (2026-10-16):** `CartBatchEndpointsIntegrationTest` (17 cases: replace on an empty cart, on a full cart, with 10 items, twice with the same result, with `[]`, each `422` violation with the cart unchanged, malformed bodies → `400` without a row; merge disjoint, summed up to exactly 10, without a cart, a sum over 10 listing both offending products, an 11-product result, `[]` without a cart (no row) and with a cart, a duplicate (no row); clear twice keeps the row, clear without a cart creates none) and the whole module's tests pass.
