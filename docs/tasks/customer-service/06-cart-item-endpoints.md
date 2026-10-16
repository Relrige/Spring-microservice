---
status: TODO
service: customer-service
---
# Cart and Cart Item Endpoints (EP-CUST-03 … EP-CUST-06)

## Context
The client keeps a local copy of the server cart to render the view and sends one request per change. These endpoints cover reading the cart and changing one line at a time. Product data (title, price, availability) is not stored; the client gets it from Catalog Service, and eligibility is checked at checkout by Order Service.
*References:* [Customer Spec — EP-CUST-03 to EP-CUST-06](../../design/services-specs/customer-service-spec.md), [API Contracts — Customer Service](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] `GET /customers/cart` → `200 OK [{productId, quantity}]`; no cart row or no items → `[]`. No lock, no cart creation.
- [ ] `POST /customers/cart/items` `{productId, quantity}` → create if missing, lock, `Cart.add`; `200 OK` with the full cart. Rule violations → `422` (`QUANTITY_LIMIT_EXCEEDED` or `TOO_MANY_PRODUCTS`).
- [ ] `PATCH /customers/cart/items/{productId}` `{quantity}` → lock if exists (no creation), `Cart.setQuantity`; no cart or no line → `404`; `quantity > 10` → `422`; `200 OK` with the full cart.
- [ ] `DELETE /customers/cart/items/{productId}` → lock if exists (no creation), `Cart.remove`; idempotent; `200 OK` with the full cart.
- [ ] Request validation: `productId` a valid UUID, `quantity` non-null and ≥ 1 → otherwise `400`.
- [ ] All responses use one response shape for the cart (a list of `{productId, quantity}`).
- [ ] Tests (integration, Testcontainers PostgreSQL, trusted headers set):
  - add a new product, increment an existing one, increment past 10 → `422`, an 11th product → `422`
  - set quantity, set on a missing line → `404`, set to 11 → `422`
  - remove twice → both `200`
  - `GET` with no cart → `[]`, and no `carts` row created
  - two customers' carts are isolated

## Technical Notes / Constraints
- Controllers stay thin: validate the DTO, call `CartService`, map the result. The rules live only in `Cart`.
- Return items in a stable order (e.g. by `productId`) so tests and the client do not depend on insertion order.
