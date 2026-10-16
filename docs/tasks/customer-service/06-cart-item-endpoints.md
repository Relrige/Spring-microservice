---
status: DONE
service: customer-service
---
# Cart and Cart Item Endpoints (EP-CUST-03 … EP-CUST-06)

## Context
The client keeps a local copy of the server cart to render the view and sends one request per change. These endpoints cover reading the cart and changing one line at a time. Product data (title, price, availability) is not stored; the client gets it from Catalog Service, and eligibility is checked at checkout by Order Service.
*References:* [Customer Spec — EP-CUST-03 to EP-CUST-06](../../design/services-specs/customer-service-spec.md), [API Contracts — Customer Service](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] `GET /customers/cart` → `200 OK [{productId, quantity}]`; no cart row or no items → `[]`. No lock, no cart creation.
- [x] `POST /customers/cart/items` `{productId, quantity}` → create if missing, lock, `Cart.add`; `200 OK` with the full cart. Rule violations → `422` (`QUANTITY_LIMIT_EXCEEDED` or `TOO_MANY_PRODUCTS`).
- [x] `PATCH /customers/cart/items/{productId}` `{quantity}` → lock if exists (no creation), `Cart.setQuantity`; no cart or no line → `404`; `quantity > 10` → `422`; `200 OK` with the full cart.
- [x] `DELETE /customers/cart/items/{productId}` → lock if exists (no creation), `Cart.remove`; idempotent; `200 OK` with the full cart.
- [x] Request validation: `productId` a valid UUID, `quantity` non-null and ≥ 1 → otherwise `400`.
- [x] All responses use one response shape for the cart (a list of `{productId, quantity}`).
- [x] Tests (integration, Testcontainers PostgreSQL, trusted headers set):
  - add a new product, increment an existing one, increment past 10 → `422`, an 11th product → `422`
  - set quantity, set on a missing line → `404`, set to 11 → `422`
  - remove twice → both `200`
  - `GET` with no cart → `[]`, and no `carts` row created
  - two customers' carts are isolated

## Technical Notes / Constraints
- Controllers stay thin: validate the DTO, call `CartService`, map the result. The rules live only in `Cart`.
- Return items in a stable order (e.g. by `productId`) so tests and the client do not depend on insertion order.

## Implementation Notes (2026-10-16)
- **`CartController`** (`/customers/cart`): `GET`, `POST /items`, `PATCH /items/{productId}`, `DELETE /items/{productId}`. Each one validates the DTO, calls `CartService` with the customer from `@AuthenticationPrincipal`, and maps the lines to `CartItemResponse` (`{productId, quantity}`). Every response is the whole cart, ordered by `productId`.
- **`CartService`** endpoint methods: `addItem` → create if missing + `Cart.add`; `setItemQuantity` → lock if exists + `Cart.setQuantity` (no cart → `Cart.empty` → `404`); `removeItem` → lock if exists + `Cart.remove`.
- **Self-invocation:** the endpoint methods call the task-05 access paths on `this`, which bypasses Spring's transactional proxy, so each endpoint method is `@Transactional` itself (otherwise `SELECT … FOR UPDATE` would run without a transaction).
- **Request DTOs** (`AddCartItemRequest`, `SetCartItemQuantityRequest`): `@NotNull` and `@Min(1)` only (→ `400`). Deliberately no `@Max(10)`: the limit is a cart rule and comes back as `422` from the aggregate. `quantity` is an `Integer`, so a missing value is a `400` field error instead of a silent `0`.
- **Test support:** `support/CartApi` calls the cart endpoints as one customer (reused by task 07); `RouteSecurityRulesTest` now also mocks `CartService`.
- **Verified (2026-10-16):** `CartItemEndpointsIntegrationTest` (11 cases: `GET` without a cart → `[]` and no row; add new and existing, ordered response; increment past 10 → `422`; new product with quantity 11 → `422`; 11th product → `422` with count; malformed bodies → `400` without creating a row; set quantity; set on a missing line or cart → `404` without creating a row; set 11 → `422`, 0 → `400`, non-UUID path → `400`; remove twice and without a cart; isolation between customers) and the whole module's tests pass.
