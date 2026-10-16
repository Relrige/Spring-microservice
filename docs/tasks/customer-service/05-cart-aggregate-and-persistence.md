---
status: DONE
service: customer-service
---
# Cart Aggregate and Persistence

## Context
`Cart` is the aggregate root: it owns its `CartItem`s and enforces the two cart rules on the result of every change. Every cart endpoint and the `OrderPaid` consumer change the cart only through its domain methods, under a pessimistic lock on the `carts` row. This task builds the model, the rules, and the create-if-missing-then-lock access path that tasks 06–08 use.
*References:* [Customer Spec — Cart Aggregate: Model, Invariants & Concurrency](../../design/services-specs/customer-service-spec.md)

## Acceptance Criteria
- [x] Flyway migration creates the schema from the spec:
  - `carts`: `customer_id` (UUID PK), `created_at`, `updated_at`
  - `cart_items`: `cart_id` (FK → `carts.customer_id`), `product_id`, `quantity` with `CHECK (quantity BETWEEN 1 AND 10)`; primary key `(cart_id, product_id)`
- [x] `Cart` entity (no `@Version`) with `@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true)` items, and a `CartItem` child entity with a composite key (`@IdClass` or `@EmbeddedId`; document the choice).
- [x] Domain methods on `Cart`, each checking the invariants on the **result** and throwing `CartRuleViolationException` (from [task 02](02-error-handling-and-validation.md)) without changing anything:
  - `add(productId, quantity)` — increments an existing line or adds a new one
  - `setQuantity(productId, quantity)` — throws `CartItemNotFoundException` if the line is missing
  - `remove(productId)` — no-op if missing
  - `replaceAll(items)` — duplicate check, then rules
  - `mergeFrom(items)` — duplicate check, sum, then rules; `QUANTITY_LIMIT_EXCEEDED` lists **every** offending product
  - `clear()` — removes all items, keeps the cart
- [x] Limits (`10` products, `10` per product) are named constants in one place.
- [x] `CartRepository`:
  - `createIfMissing(customerId)` — native `INSERT INTO carts … ON CONFLICT (customer_id) DO NOTHING`
  - `findByIdForUpdate(customerId)` — `@Lock(PESSIMISTIC_WRITE)`, returns `Optional<Cart>`
  - a plain read for EP-CUST-03 that takes no lock
- [x] A `CartService` method pair that every write goes through: "create if missing, then lock" (for operations that may add items) and "lock if exists" (for operations that only modify or remove). Each runs in one transaction and sets `updatedAt`.
- [x] Tests:
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

## Implementation Notes (2026-10-16)
- **Schema (`V2__create_cart_tables.sql`):** `carts` (`customer_id` PK, `created_at`, `updated_at`) and `cart_items` (`cart_id` FK → `carts.customer_id`, `product_id`, `quantity CHECK (quantity BETWEEN 1 AND 10)`, PK `(cart_id, product_id)`), as in the spec.
- **`CartItem` as a child entity with `@IdClass(CartItemId)`**, where the `@Id @ManyToOne cart` is part of the key (derived identity). `@IdClass` keeps the entity flat (`productId` is a plain field) instead of the `@EmbeddedId` + `@MapsId` pair. The constructor and `changeQuantity` are package-private, so only `Cart` creates and changes lines; there is no `CartItem` repository.
- **`CartLine`** is a value record (`productId`, `quantity`): it is what `Cart.lines()` returns and what batch methods take, so `CartItem` entities never leave the aggregate. `CartLine.requireNoDuplicates` is static so task 07 can check a request before taking the lock.
- **Domain methods** as listed in the task. Each checks the rules on the result before changing anything. Sums use `long`, so a huge quantity cannot overflow past the limit check. `QUANTITY_LIMIT_EXCEEDED` lists every offending product. Quantities `< 1` are rejected with `IllegalArgumentException`: request validation returns `400` first, so reaching it is a programming error.
- **Batch changes are applied as a difference** (update lines that stay, remove lines that go, add new ones), not "delete all, insert all". Hibernate flushes inserts before deletes, so re-inserting a product that is still in the cart would hit the primary key within one flush. `CartRepositoryTest.replaceAll_keepingAProduct_doesNotHitThePrimaryKey` covers this.
- **Clearing** uses `items.clear()` with `orphanRemoval`, not a bulk JPQL delete, so the persistence context stays consistent; carts hold at most 10 lines, so per-row deletes are cheap.
- **No `@Version`:** the pessimistic lock already serialises every writer of a cart, so a version check could never fail. **No `ON CONFLICT` upsert for items:** under the lock, read-then-insert-or-update is safe. The only native statement is `CartRepository.createIfMissing` (`INSERT … ON CONFLICT (customer_id) DO NOTHING`, `@Transactional @Modifying`).
- **`updatedAt`** is set by the aggregate on every change (`touch()`), not by `@UpdateTimestamp`: changing only items does not make the `Cart` row dirty, so Hibernate would never update the timestamp.
- **Access paths in `CartService`:** `changeCreatingIfMissing(customerId, change)` (create if missing, `findByIdForUpdate` = `SELECT … FOR UPDATE`, apply) and `changeIfExists(customerId, change)` (lock if the row exists; otherwise the change runs on `Cart.empty(customerId)`, a cart that is never stored, so remove and clear do nothing and set-quantity finds no line). Both take a `Consumer<Cart>` and return the resulting lines in one transaction. `getCart` reads without a lock and never creates a row.
- **Verified (2026-10-16):** `CartTest` (26 unit cases: every method, each rule with its boundary, unchanged cart after a rejection, overflow, ordering, `updatedAt`), `CartRepositoryTest` (7 cases: schema `validate`, `createIfMissing` twice, stored changes, replace without a PK clash, clear keeps the row, `updatedAt`, `CHECK` and FK), `CartServiceConcurrencyTest` (5 cases: 8 parallel first adds → one cart and 8 lines; 10 parallel `add(p, 1)` → 10; 11 parallel increments → exactly one `QUANTITY_LIMIT_EXCEEDED`; 11 parallel new products → exactly one `TOO_MANY_PRODUCTS`; reads and removals without a cart create no row) and the whole module's tests pass.
