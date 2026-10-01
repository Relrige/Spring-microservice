# Catalog Manager — User Stories (Draft)

This document captures the functional requirements from the perspective of the **Catalog Manager** actor.

---

## 1. Product Management

* **US-CM-01: Product Creation**
  * **As a** catalog manager,
  * **I want to** create a new product with a title, description, base price, and category in a `NOT ACTIVE` state,
  * **So that** I can prepare product listings before they are made public to customers.

* **US-CM-02: Activate Product**
  * **As a** catalog manager,
  * **I want to** activate a product to transition it to `ACTIVE` status,
  * **So that** it becomes visible on the public storefront and eligible for customer purchase.

* **US-CM-03: Deactivate Product (Soft Delete)**
  * **As a** catalog manager,
  * **I want to** deactivate an active product when it is discontinued,
  * **So that** it is hidden from the public catalog and cannot be added to new carts, while keeping historical references intact for past orders and warehouse balances.

* **US-CM-04: Update Product Information**
  * **As a** catalog manager,
  * **I want to** edit an existing product's title, description, category, and base price,
  * **So that** customer-facing product information and pricing stay accurate.

---

## 2. Category Management

* **US-CM-05: Category Management**
  * **As a** catalog manager,
  * **I want to** create, rename, delete, and view product categories,
  * **So that** products can be organized in a clear one-to-many hierarchy for customer navigation.
  * *Notes:* 
    - Each product belongs to exactly one category.
    - Deleting a category that currently contains products is strictly forbidden (must reassign or remove products first).

---

## 3. Catalog Overview & Inspection

* **US-CM-06: Internal Catalog Overview**
  * **As a** catalog manager,
  * **I want to** view a complete list of all products and filter by status (`ACTIVE` vs. `NOT ACTIVE`) and category,
  * **So that** I have full operational oversight over the store's assortment.
