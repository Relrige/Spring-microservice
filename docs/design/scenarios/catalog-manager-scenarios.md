# Catalog Manager Scenarios

> Draft interaction scenarios for the Catalog Manager actor.
> The Catalog Manager is an internal staff role authenticated via Auth Service with `ROLE_CATALOG_MANAGER`.

---

## SCN-CM01: Create New Product

1. Manager sends `POST /catalog/products` with `{title, description, categoryId, price}` and JWT (`ROLE_CATALOG_MANAGER`).
2. Catalog Service validates: category exists, `price > 0`, required fields present.
3. Catalog Service creates `Product(id=UUID, title, description, categoryId, price, status=NOT_ACTIVE)`.
4. Catalog Service publishes event: `ProductCreated(productId)`.
5. Inventory Service consumes `ProductCreated` → idempotently creates `StockItem(productId, availableQty=0, reservedQty=0, packaging=null)`.
6. Catalog Service returns `201 Created` with `{productId}`.

*Products are always created in `NOT_ACTIVE` state. The manager prepares the listing before publishing.*

---

## SCN-CM02: Activate Product

1. Manager sends `PATCH /catalog/products/{productId}/status` with `{status: ACTIVE}` and JWT.
2. Catalog Service calls Inventory Service synchronously: `GET /inventory/stock/{productId}/readiness`.
   - Inventory Service checks: `StockItem` exists for `productId` AND packaging dimensions (weight, L×W×H) are all set.
3. If readiness check fails → Catalog Service returns `422` with specific reason (e.g., `"packaging_dimensions_missing"`).
4. If readiness check passes → Catalog Service sets `Product.status = ACTIVE`.
5. Returns `200 OK`.

*This sync check introduces deliberate Catalog→Inventory coupling at activation time. Accepted trade-off: a product that cannot be shipped should not be sold.*

---

## SCN-CM03: Deactivate Product (Soft Delete)

1. Manager sends `PATCH /catalog/products/{productId}/status` with `{status: NOT_ACTIVE}` and JWT.
2. Catalog Service sets `Product.status = NOT_ACTIVE`.
3. Returns `200 OK`.

*No domain event is published. Deactivation propagates passively:*
- *Cart display: on next cart fetch, the client shows the item with "Unavailable" badge (SCN-C08).*
- *Soft check: Order Service queries Catalog synchronously and blocks checkout for any cart containing this item (SCN-C11).*
- *Hard reservation: Order Service queries Catalog synchronously and rejects any order submission containing this item (SCN-C15).*
- *No service maintains a local cache of product status, so there is no consumer for a `ProductDeactivated` event.*
- *Hard deletes are forbidden. The product record is preserved for order history integrity.*

---

## SCN-CM04: Update Product Information

1. Manager sends `PUT /catalog/products/{productId}` with `{title, description, categoryId, price}` and JWT.
2. Catalog Service validates: category exists, `price > 0`.
3. Catalog Service updates the product fields.
4. Returns `200 OK`.

*Price changes affect only future orders. Past orders retain their `OrderItemSnapshot` prices.*

---

## SCN-CM05: Create Category

1. Manager sends `POST /catalog/categories` with `{name}` and JWT.
2. Catalog Service validates: name is non-empty and unique.
3. Catalog Service creates `Category(id, name)`.
4. Returns `201 Created` with `{categoryId}`.

---

## SCN-CM06: Rename Category

1. Manager sends `PUT /catalog/categories/{categoryId}` with `{name}` and JWT.
2. Catalog Service validates: category exists, new name is non-empty.
3. Catalog Service updates `Category.name`.
4. Returns `200 OK`.

---

## SCN-CM07: Delete Category — No Products Assigned

1. Manager sends `DELETE /catalog/categories/{categoryId}` and JWT.
2. Catalog Service checks: no products currently assigned to this category.
3. Check passes → Catalog Service deletes the category.
4. Returns `200 OK`.

---

## SCN-CM08: Delete Category — Products Exist (Blocked)

1. Manager sends `DELETE /catalog/categories/{categoryId}` and JWT.
2. Catalog Service checks: one or more products are assigned to this category.
3. Returns `409 Conflict` with message: "Category has associated products. Reassign or remove them first."

---

## SCN-CM09: View Internal Catalog Overview

1. Manager sends `GET /catalog/admin/products` with optional filters `{status, categoryId}` and JWT.
2. Catalog Service returns the full product list including `NOT_ACTIVE` products (admin view), with pagination.
3. Each item includes: `productId`, `title`, `category`, `price`, `status`.
