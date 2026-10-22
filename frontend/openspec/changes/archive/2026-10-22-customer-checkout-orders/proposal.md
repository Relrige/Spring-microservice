# Proposal

## Why

Customers can fill a cart, but "Proceed to checkout" is a stub, so nothing can be bought and Order Service is never shown. Order Service already offers the whole customer flow (EP-ORD-01…06) and Customer Service the profile (EP-CUST-01/02), all routed by the gateway. This iteration builds that flow end to end, which completes the customer side of the demo.

## What Changes

- **Catalog types brought up to date.** Catalog's `public.json` changed in Catalog tasks 27–29 (EP-CAT-05 now answers `ProductResponse`, there is a new `ids` filter), so `npm run check:api` fails on the current branch. The generated types are regenerated, and the cart and product pages are adapted; storefront behaviour does not change.
- **Profile:** `/account/profile` shows and saves first name, last name, phone and default address. Field rules follow EP-CUST-02, and the server's field errors appear on the form.
- **Checkout entry from the cart:**
  - "Proceed to checkout" runs the soft check (`POST /orders/validate`) for the whole cart.
  - Products that are inactive or out of stock are reported on the cart page.
  - The button is disabled while the cart shows an unavailable line.
- **Delivery step** (`/checkout`):
  - A delivery form (recipient, phone, address, carrier) pre-filled from the profile, with an option to save the details to the profile.
  - The carrier is Nova Poshta only (BL-11, workaround).
  - Submitting sends `POST /orders` with an `Idempotency-Key`: one key per checkout attempt, reused for a retry and replaced when the cart or the details change (BL-07, delivered on the backend; verified by this iteration).
  - Problems that leave the customer on the form: `422` ineligible items, insufficient stock and order total too large; `409` request in progress; `422` key mismatch.
- **Payment step** (`/checkout/:orderId`), as in the checkout mockup:
  - The placed order with its "pay by" deadline and the delivery details.
  - A card form checked with Payment's rules; card data is never stored.
  - The test cards of the demo payment provider.
  - Answers to a payment: decline (`402`) with retry by another card, an elapsed window, a payment still in progress, and an unavailable service with a safe retry by the same card.
  - On success, the order page opens and the cart is read again: Customer Service removes the ordered products itself.
- **Orders:**
  - `/account/orders` lists the customer's orders, newest first and paged, with status chips.
  - `/account/orders/:orderId` shows the items with order-time prices, the total, delivery details, payment time, TTN and delivery status, a "Pay now" link while payment is awaited, and "Cancel order" with a confirmation.
  - Cancelling shows `CANCELLED` or `Cancelling` from the `202` answer.
  - Pages showing an order that can still change poll every 5 s while visible (BL-13, workaround).
- **Navigation:** the customer header gains "Orders" and "Profile"; `/checkout…` needs `CUSTOMER`; `/account` leads to the orders list.
- **Mock backend, demo data, seed:**
  - Mock handlers for profile and the six customer order endpoints, with the spec's status codes and problem types.
  - Mock stock reservation, the demo payment provider's test cards and the expiry of the payment window.
  - A paid order becomes ready to ship with a TTN after a short delay, standing in for Delivery.
  - The ordered lines are removed from the cart on payment.
  - A demo profile for the demo customer, shared with `npm run seed`, which sets it while the profile is empty.
- **Browser journeys:** checkout with a declined then an approved card, and cancelling an unpaid order, against the mock and the live stack.

Not in this change: staff order views (BL-10), push updates (BL-13; polling stands in), a carrier list (BL-11), the worker's fulfillment queue (iteration 5).

## Capabilities

### New Capabilities
- `customer-profile`: the customer's profile page and its use as the source of delivery details.
- `checkout`: from the cart to a placed and paid order — soft check, delivery details, order submission with an idempotency key, card payment and the payment window.
- `customer-orders`: the customer's order history and order details, the order status display, polling, and cancellation.

### Modified Capabilities
- `shopping-cart`: *Checkout entry* — for a customer "Proceed to checkout" runs the soft check and opens the checkout; it is disabled while an unavailable line is in the cart. The cart is read again after a payment.
- `app-navigation`: *URL areas per role* adds `/checkout` (requires `CUSTOMER`); *Role-aware navigation* shows customers "Orders" and "Profile".

## Impact

- Code under `frontend/` only; no service, gateway or spec changes. BL-07 moves to `Done` once the live run shows the replay; BL-11 and BL-13 stay `Open`, with workarounds marked in code.
- New gateway routes used by the app:
  - `GET`, `PUT /customers/me/profile`;
  - `POST /orders/validate`, `POST /orders`, `POST /orders/{orderId}/pay`;
  - `GET /orders`, `GET /orders/{orderId}`, `DELETE /orders/{orderId}`.

  All of them need `CUSTOMER`.
- New code:
  - `src/api/order.ts` and the profile calls in `src/api/customer.ts`;
  - feature folders for the profile, checkout and orders;
  - a date formatter (`18 Oct 2026, 14:05`);
  - mock order and profile tables and handlers;
  - a demo profile fixture, a seed step, and new journeys.
- Regenerated `src/api/generated/catalog.ts`, with the Catalog aliases adapted.
- Live runs now also need Order and Payment running. On the current local stack Order Service does not start (its database volume holds an older schema), so its volume needs a reset before the live verification.
