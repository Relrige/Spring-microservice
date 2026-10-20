# Proposal

## Why

The frontend lets visitors browse the catalog, but there is no way to collect products for purchase. Customer Service already keeps a server cart per customer with limits and a replace/keep/merge contract for the guest cart on login (EP-CUST-03…09), and the gateway routes all of it. This iteration builds the cart that iteration 3 (checkout) needs, and shows Customer Service end to end.

## What Changes

- **Guest cart:** a visitor who is not logged in can add products, change quantities and remove lines. The cart lives only in the browser (`localStorage`), follows the same limits as the server (at most 10 products, quantity 1–10) and stays in sync across tabs.
- **Server cart:** for a logged-in customer every change goes to Customer Service (`/customers/cart*`), and the cart shown is always the server's answer. `422` cart-rule violations, a line already removed on another device (`404`) and unavailable services are shown in plain words, and the cart stays correct.
- **Guest cart on login:** after a customer logs in or registers with a non-empty guest cart, the frontend applies the rule of the Customer Service spec. If the account cart is empty, the guest cart replaces it silently. If both carts have items, the customer chooses: replace the account cart, keep the account cart, or merge the two (quantities summed). The guest cart is discarded afterwards.
- **Add to cart** on the product details page with a quantity selector (1–10), as in the product mockup. Out-of-stock products cannot be added.
- **Cart page** `/cart` as in the cart mockup:
  - each line shows live product data (title, unit price, line total, image placeholder) fetched from Catalog;
  - a line whose product is no longer available (`404`, inactive) or out of stock is marked "Unavailable", left out of the estimated subtotal, and can still be removed (US-CUST-04);
  - quantity stepper, remove and clear;
  - a summary with item count and estimated subtotal;
  - "Proceed to checkout" leads to login for visitors; for customers it waits for iteration 3.
- **Header:** a cart button with an item-count badge for visitors and customers; staff see no cart.
- **Mock backend and demo data:** mock handlers for the seven cart endpoints that follow the spec's status codes and `422` violations. A demo cart for the demo customer is shared with the seed script, so the replace/keep/merge choice can be shown at once. `npm run seed` gives the demo customer that cart when their cart is empty.
- **Browser journeys** for the guest cart, the login choice and the server cart, against the mock and the live stack.

Not in this change: checkout, payment, orders and the customer profile (iteration 3). Customer Service does not check products when they are added; availability is checked again at checkout (EP-ORD-01), also in iteration 3.

## Capabilities

### New Capabilities
- `shopping-cart`: the guest cart and the customer's server cart, their limits, the cart page with live product data and unavailable lines, the header cart count, and the replace/keep/merge choice when a customer logs in with a guest cart.

### Modified Capabilities
- `catalog-browsing`: the product details page gains a quantity selector and an "Add to cart" action (disabled for out-of-stock products; not shown to staff).
- `app-navigation`: the storefront area gains `/cart`, which is open to visitors and customers and denied to staff; the header shows the cart with its item count to visitors and customers only.

## Impact

- Code under `frontend/` only; no service, gateway or spec changes, and no new backlog items are expected.
- New gateway routes used by the app: `GET`, `PUT`, `DELETE /customers/cart`; `POST /customers/cart/items`; `PATCH`, `DELETE /customers/cart/items/{productId}`; `POST /customers/cart/merge`. All need role `CUSTOMER`. Catalog's `GET /catalog/products/{id}` is called once per cart line (at most 10).
- New code:
  - `src/api/customer.ts`;
  - a cart feature folder (`src/features/cart/`) with its state, page and login choice;
  - mock handlers and a mock cart table;
  - a demo cart fixture;
  - a seed step and new Playwright journeys.
- The live journeys and the seed now also need Customer Service running in the Compose stack.
