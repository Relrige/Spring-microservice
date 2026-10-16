---
status: TODO
service: customer-service
---
# Cart Aggregate and Persistence

## Context
`Cart` is the aggregate root: it owns its `CartItem`s and enforces the two cart rules on the result of every change. Every cart endpoint and the `OrderPaid` consumer change the cart only through its domain methods, under a pessimistic lock on the `carts` row. This task builds the model, the rules, and the create-if-missing-then-lock access path that tasks 06–08 use.
*References:* [Customer Spec — Cart Aggregate: Model, Invariants & Concurrency](../../design/services-specs/customer-service-spec.md)

## Acceptance Criteria
- [ ] Flyway migration creates the schema from the spec:
  - `carts`: `customer_id` (UUID PK), `created_at`, `updated_at`
  - `cart_items`: `cart_id` (FK → `carts.customer_id`), `product_id`, `quantity` with `CHECK (quantity BETWEEN 1 AND 10)`; primary key `(cart_id, product_id)`
- [ ] `Cart` entity (no `@Version`) with `@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true)` items, and a `CartItem` child entity with a composite key (`@IdClass` or `@EmbeddedId`; document the choice).
- [ ] Domain methods on `Cart`, each checking the invariants on the **result** and throwing `CartRuleViolationException` (from [task 02](02-error-handling-and-validation.md)) without changing anything:
  - `add(productId, quantity)` — increments an existing line or adds a new one
  - `setQuantity(productId, quantity)` — throws `CartItemNotFoundException` if the line is missing
  - `remove(productId)` — no-op if missing
  - `replaceAll(items)` — duplicate check, then rules
  - `mergeFrom(items)` — duplicate check, sum, then rules; `QUANTITY_LIMIT_EXCEEDED` lists **every** offending product
  - `clear()` — removes all items, keeps the cart
- [ ] Limits (`10` products, `10` per product) are named constants in one place.
- [ ] `CartRepository`:
  - `createIfMissing(customerId)` — native `INSERT INTO carts … ON CONFLICT (customer_id) DO NOTHING`
  - `findByIdForUpdate(customerId)` — `@Lock(PESSIMISTIC_WRITE)`, returns `Optional<Cart>`
  - a plain read for EP-CUST-03 that takes no lock
- [ ] A `CartService` method pair that every write goes through: "create if missing, then lock" (for operations that may add items) and "lock if exists" (for operations that only modify or remove). Each runs in one transaction and sets `updatedAt`.
- [ ] Tests:
  - unit tests for every domain method, covering each rule and its boundary (10 allowed, 11 rejected; quantity 10 allowed, 11 rejected; sum exactly 10 allowed) and that a rejected operation leaves the cart unchanged
  - a Testcontainers repository test: schema `validate`, the `CHECK` constraint, `createIfMissing` twice gives one row, `clear()` keeps the `carts` row
  - a concurrency test (Testcontainers, several threads released together):
    - parallel first adds for a customer with no cart → one `carts` row, no duplicate-key error
    - parallel `add(p, 1)` × 10 → quantity exactly 10
    - an 11th parallel add → exactly one `422`

## Technical Notes / Constraints
- `createIfMissing` is a native modifying query, so it needs `@Modifying` and a surrounding transaction. Run it **before** the locking `SELECT` in the same transaction.
- `CartItem` has no meaning outside its cart; do not give it its own repository.
- Explain in the task notes why there is no `@Version` (the pessimistic lock already serialises writers) and why item writes need no `ON CONFLICT` upsert (the cart lock makes read-then-write safe).
- Bulk deletes through JPQL bypass `orphanRemoval` and the persistence context. Prefer `items.clear()` through the aggregate, or record why a bulk delete is used.
