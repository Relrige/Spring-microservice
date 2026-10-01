# Catalog Manager — Technical & Architectural Insights

This document captures architectural discoveries, domain boundaries, and technical trade-offs relevant to the **Catalog Manager** actor and the **Catalog Service**.

---

## 1. Product Identifier: Dropping SKU in Favor of `productId`
- **Context:** The initial architectural proposal suggested using both an internal database `id` and a business `SKU` string across domains.
- **Challenge:** In physical retail, a SKU represents a barcode printed on a warehouse box. In our virtual university scope, we have no barcode scanners or external ERPs.
- **Architectural Decision:**
  - `Catalog Service` is the **Master Domain** (Source of Truth) for products and generates the unique `productId` (e.g., UUID or numeric ID).
  - Downstream services (`Inventory Service`, `Order Service`) reference this `productId` directly.
  - Just as `Order Service` stores a surrogate `customerId` issued by `Auth Service`, it is architecturally sound and clean for `Inventory Service` and `Order Service` to store the surrogate `productId` issued by `Catalog Service`.
  - Eliminating the redundant `SKU` field simplifies data models and prevents unnecessary field duplication across all services.

---

## 2. Product Lifecycle: Two-State Enum Model (`ProductStatus`)
- **Decision:** The system adopts an extensible Enum `ProductStatus` with two initial states: `ACTIVE` and `NOT_ACTIVE`.
- **Rationale for Enum:** Using an Enum instead of a boolean flag (`isActive`) allows seamless future extension if additional states (e.g., `DRAFT`, `ARCHIVED`, `SUSPENDED`) are introduced without altering table schemas or breaking contracts.
- **Initial State:** Products are created in the `NOT_ACTIVE` state by default. This allows the catalog manager to prepare the title, description, category, and pricing before publishing.
- **Activation:** The manager explicitly sets the status to `ACTIVE` to make the product visible on the storefront for customer browsing and purchase.
- **Deactivation (Soft Delete):** When a product is discontinued, its status is changed to `NOT_ACTIVE`. It is hidden from customer browsing and search, and blocked from new cart additions, while remaining safely preserved in the database to guarantee referential integrity for historical orders (`OrderItemSnapshot`) and warehouse records. Hard deletes are forbidden.

---

## 3. Category Relationship & Deletion Constraint
- **Structure:** `Category` is a distinct entity with `id` and `name`.
- **Relationship:** One-to-Many (`Category` $1 \to N$ `Product`). Each product belongs to exactly one category (not multi-category tags).
- **Specs Approach:** In line with keeping initial focus on microservices and avoiding over-engineered faceted search, product specifications will start as plain text descriptions.
- **Deletion Constraint:** Deleting a category that currently has associated products is prohibited. This prevents orphaned products in the catalog and preserves navigation integrity. Products must be reassigned to another category before deletion.

---

## 4. Deactivation Impact on Customer Carts and Checkouts
- **Cross-Domain Ripple Effect:** Deactivating a product does not remove it from customer carts immediately, because carts are distributed across client devices or the Cart subsystem.
- **Enforcement Rules:**
  - **Cart Display:** If an item in a cart becomes `NOT_ACTIVE`, it remains visible in the cart with an "Unavailable" badge, is disabled, and its price is excluded from the subtotal.
  - **Checkout Pre-Check:** Transition from cart to checkout is blocked if any item is `NOT_ACTIVE`.
  - **Order Creation Guard:** `Order Service` verifies with `Catalog Service` that all items are `ACTIVE` before creating an order. Any inactive item causes an immediate order rejection.
