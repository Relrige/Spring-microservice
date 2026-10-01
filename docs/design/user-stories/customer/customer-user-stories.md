# Customer User Stories (Draft)

This document captures the functional requirements from the perspective of customer system actor.

### Browsing & Discovery
* **US-CUST-01: Product Discovery & Filtering**
  * **As a** customer,
  * **I want to** view, search, filter, and sort products by name, specifications, and price,
  * **So that** I can easily find and select items I wish to buy.
  * *Notes:* Filter results should indicate coarse availability ("In Stock" / "Out of Stock").

* **US-CUST-02: Product Detail Inspection**
  * **As a** customer,
  * **I want to** view detailed information about an individual product (title, price, description, technical characteristics, stock status, delivery options),
  * **So that** I can make an informed purchasing decision.

### Cart Management
* **US-CUST-03: Add Product to Cart**
  * **As a** customer,
  * **I want to** add products to my cart,
  * **So that** I can collect items for purchase later.

* **US-CUST-04: Modify Cart Items & Handle Unavailable Products**
  * **As a** customer,
  * **I want to** change item quantities, remove items from my cart, and clearly see when a previously added item has become unavailable,
  * **So that** I can adjust my selection before checkout without items silently disappearing.
  * *Notes:* If an item in the cart is deactivated by a manager or runs out of stock, it remains visible with an "Unavailable" badge, is disabled, its price is excluded from the subtotal, and can be removed.

* **US-CUST-05: Cross-Device Cart Synchronization**
  * **As a** registered customer,
  * **I want** my cart to be saved on my account,
  * **So that** I can resume shopping across different devices.
  * *Notes:* Guest carts are kept on the client-side and merged into the account cart upon login.

### Identity & Account
* **US-CUST-06: Customer Registration & Login**
  * **As a** customer,
  * **I want to** register and authenticate into my personal account,
  * **So that** I can access my saved cart, profile data, and order history.

* **US-CUST-07: Guest Checkout & Secret URL Access**
  * **As a** guest customer,
  * **I want to** place an order without creating an account and access its status via a secret link sent to my email,
  * **So that** I can complete a purchase quickly while retaining the ability to check order status and track shipping.
  * *Notes:* Order is assigned a UUID; access link with token is sent to the checkout email.

### Checkout & Payment
* **US-CUST-08: Checkout & Delivery Selection**
  * **As a** customer,
  * **I want to** proceed from my basket to the checkout form and select delivery details (recipient name, phone, address/branch, postal carrier),
  * **So that** the store knows where and to whom to deliver the items.
  * *Notes:* 
    - Upon clicking "Proceed to Checkout", the system performs a soft check confirming all cart items are both in stock and currently active before loading the form.
    - If logged in, delivery details pre-fill from the customer profile.

* **US-CUST-09: Out-of-Stock & Deactivation Guard at Order Submission**
  * **As a** customer,
  * **I want** stock to be securely reserved and product availability verified when I submit my order, or be notified if an item became unavailable or deactivated while I filled the form,
  * **So that** I only proceed to payment if the physical items are guaranteed and valid.
  * *Notes:* Submitting the order form attempts an atomic stock reservation and confirms all items are active. If an item ran out or was deactivated, the user is prevented from proceeding to payment and prompted to adjust their cart.

* **US-CUST-10: Card Payment Processing**
  * **As a** customer,
  * **I want to** pay for my order using a credit/debit card,
  * **So that** my purchase is confirmed and processed for fulfillment.

* **US-CUST-11: Payment Failure & Retry with Reservation Timeout**
  * **As a** customer,
  * **I want** my order to be preserved if the payment fails,
  * **So that** I can retry payment using another card or method.
  * *Notes:* Stock is reserved with a timeout window (e.g., 15 minutes). If payment is not completed before expiry, the stock reservation is released.

### Post-Purchase, Tracking & Cancellation
* **US-CUST-12: Order Notifications**
  * **As a** customer,
  * **I want to** receive email notifications on key order events (Order Placed, Payment Received, Parcel Shipped),
  * **So that** I stay updated on my order's progress.

* **US-CUST-13: Order Cancellation & Refund**
  * **As a** customer,
  * **I want to** cancel an order that has been paid and receive a refund,
  * **So that** I can retract my purchase if I change my mind.
  * *Notes:* 
    - Registered customers cancel via their account.
    - Guest customers must confirm cancellation via a one-time verification link or code sent to their email.
    - Cancellation is permitted only before the order has been handed to the carrier (shipped). Once delivered, returns are out of scope.

* **US-CUST-14: Order History Viewing**
  * **As a** registered customer,
  * **I want to** view a historical list of all my past orders and their final statuses,
  * **So that** I can keep track of my purchases.

* **US-CUST-15: Order Details & Price Snapshot Inspection**
  * **As a** customer,
  * **I want to** view detailed information for a specific order (including the purchase price at the time of ordering),
  * **So that** I have an accurate receipt regardless of future catalog price changes.

* **US-CUST-16: Delivery Tracking (TTN)**
  * **As a** customer,
  * **I want to** see the postal tracking code (TTN) and shipping status on my order details page,
  * **So that** I know when my parcel will arrive.
