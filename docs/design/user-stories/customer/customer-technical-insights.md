# Technical & Architectural Insights

This document captures architectural discoveries, domain boundaries, and technical trade-offs identified during customer user story design.

---

## 1. Catalog & Inventory Decoupling: Stock Status
- **User Story Context:** US-CUST-01 & US-CUST-02 (Browsing products with stock availability).
- **Challenge:** Avoid having `Catalog Service` query `Inventory Service` synchronously on every page view or search query, which causes high coupling and database contention.
- **Insight / Approach:**
  - `Catalog Service` displays a coarse availability indicator (`IN_STOCK` vs. `OUT_OF_STOCK`), not exact quantities.
  - `Inventory Service` publishes asynchronous domain events (`ProductStockDepleted`, `ProductStockReplenished`) whenever an item crosses the threshold.
  - `Catalog Service` consumes these events and updates its read-model.

---

## 2. Order Placement & Two-Phase Product / Stock Verification
- **User Story Context:** US-CUST-08, US-CUST-09, US-CUST-11 (Cart $\to$ Checkout $\to$ Payment).
- **Decision & Architecture:**
  - **Phase 1: Transition from Cart to Checkout (Soft Check):** When the customer clicks "Proceed to Checkout", the system validates that all cart items are currently available in stock (`Inventory Service`) and have `ProductStatus.ACTIVE` (`Catalog Service`). If any item is missing or inactive, checkout is blocked and the customer is prompted to adjust their cart. No stock is locked yet.
  - **Phase 2: Transition from Checkout Form to Payment (Hard Reservation & Status Confirmation):** When the customer submits the order form ("Submit & Pay"), `Order Service` verifies active status and requests an atomic stock reservation from `Inventory Service`. The order is created in `PENDING_PAYMENT`.
  - **Out-of-Stock & Deactivation Guard at Submission:** If an item ran out or was deactivated while the user filled the form, the submission is rejected, the user is notified immediately, and payment is prevented.
  - **Reservation Timeout (TTL):** Successfully reserved stock has an expiration timer (e.g., 15 minutes). If payment confirmation is not received within this window, the reservation expires and stock is automatically released back to the warehouse.
  - **Scope Boundary:** Complex anti-abuse rate-limiting and artificial per-order quantity ceilings are deemed out of scope for the academic project; the design relies on the reservation TTL to prevent permanent inventory starvation.

---

## 3. Identity, Auth & Customer Profile Separation
- **Context:** Managing authentication across internal staff vs. external retail customers.
- **Challenge:** Internal roles (Catalog Manager, Inventory Worker) only need credentials and roles, while Customers have rich profile data (shipping addresses, phone numbers, order preferences).
- **Insight / Approach:**
  - **`Auth Service`:** Dedicated solely to identity, authentication, credential validation, and issuing role-based JWTs (`ROLE_CUSTOMER`, `ROLE_CATALOG_MANAGER`, `ROLE_INVENTORY_WORKER`, `ROLE_ADMIN`).
  - **`Customer Service`:** Manages customer profile entities (first/last name, phone, saved delivery addresses), keyed by the customer ID from the JWT.
  - Keeps authentication lean and isolated, while customer profile changes don't affect core auth tokens.

---

## 4. Guest Order Access & Minimal Identification
- **User Story Context:** US-CUST-07 & US-CUST-13 (Guest order viewing and cancellation).
- **Challenge:** Enabling unregistered customers to view their order and perform authorized actions (like cancellation) without a login account.
- **Insight / Approach:**
  - **Order UUID & Secret URL:** Each order is assigned a public UUID. Guest customers receive an access link via the email entered at checkout (e.g., `/orders/{uuid}?token={orderSecretToken}`).
  - **Email as Minimal Identity:** For actions requiring authorization (e.g., order cancellation):
    - If the request lacks a JWT token (guest), the system dispatches a one-time verification link or code to the customer's email.
    - Only upon confirming via that link does the cancellation Saga execute.
    - Prevents unauthorized cancellations by third parties guessing an order ID.

---

## 5. Cart Architecture & Unavailable Product Handling
- **User Story Context:** US-CUST-03, US-CUST-04, US-CUST-05 (Cart persistence and multi-device).
- **Insight / Approach:**
  - **Guest Carts:** Stored entirely client-side (e.g., browser `localStorage`), keeping backend microservices stateless for anonymous browsing.
  - **Account Carts:** Persisted on the backend for authenticated customers to enable cross-device synchronization.
  - **Merge on Login:** When a guest customer logs into their account, the client submits the local guest cart items to be merged into the account's backend cart.
  - **Handling Inactive & Out-of-Stock Items in Cart:**
    - Products in a cart that are deactivated (`NOT_ACTIVE`) or out of stock are **not silently deleted**. Silent deletion creates severe customer confusion.
    - Instead, the cart hydrates fresh item states: unavailable products are grayed out with a clear "Unavailable / Discontinued" badge, quantity controls are disabled, their price is excluded from the subtotal, and a "Remove" action is provided.

---

## 6. Order Cancellation & Distributed Saga Compensation
- **User Story Context:** US-CUST-13 (Cancellation and refund).
- **Insight / Approach:**
  - Cancellation triggers a compensating distributed transaction (Saga):
    1. `Order Service` changes status to `CANCELLATION_REQUESTED`.
    2. `Inventory Service` releases reserved stock back to available warehouse balance.
    3. `Delivery Service` cancels registered consignment / waybill (TTN).
    4. `Payment Service` triggers a refund transaction with the payment gateway.
    5. `Order Service` updates status to `CANCELLED`.
  - **Business Boundary:** Direct cancellation is only permitted while the order is in `PENDING` or `PAID` state, *before* it has been handed over to the carrier (`SHIPPED`).

---

## 7. Event-Driven Asynchronous Notifications
- **User Story Context:** US-CUST-12 (Order status emails).
- **Insight / Approach:**
  - A dedicated `Notification Service` listens to domain events (`OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderCancelled`).
  - Keeps the synchronous order checkout and payment processing resilient and fast by decoupling email delivery from transactional flows.
