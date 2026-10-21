# Design

## Context

- Iteration 1 (archived `2026-10-20-frontend-foundation`) is in place. Its decisions still hold and are referred to below as *foundation D1–D16*:
  - one API layer with hand-written types (D4);
  - TanStack Query for server state; mutations are never retried (D3);
  - the session in React context with `localStorage` and cross-tab sync (D5);
  - the MSW mock backend shared by every test level (D10);
  - demo fixtures shared by the mock and the seed script (D14);
  - Mantine with the generated theme (D7, D16).
- Customer Service contract: `../docs/design/services-specs/customer-service-spec.md`.
  - Every cart answer is the whole cart, `[{ productId, quantity }]` ordered by `productId`.
  - `422` answers carry `violation` ∈ `TOO_MANY_PRODUCTS | QUANTITY_LIMIT_EXCEEDED | DUPLICATE_PRODUCT` with details; `404` comes only from `PATCH` of a missing line; `DELETE` is idempotent.
  - Merge is **not** idempotent: a retry after a lost answer sums the quantities twice.
  - Products are not checked when added.
  - All routes need role `CUSTOMER` and are routed by the gateway (`api-gateway/src/main/resources/application.yml`).
- Catalog has no public batch read (EP-CAT-09 is internal), so the cart reads each line's product with EP-CAT-05. At most 10 lines means at most 10 requests.
- Mockups: `docs/design/screens/Cart.dc.html` (lines, unavailable line, summary) and `Product.dc.html` (quantity and "Add to cart"). The storefront list mockup (`Main.dc.html`) has no add button on the tiles.

## Goals / Non-Goals

**Goals:**
- Pages and the header use one cart interface, whatever the cart's owner (guest or customer).
- The customer's cart on screen never drifts from Customer Service: the server's answer is always what is shown.
- Every rule of the Customer Service spec's *Client usage* section is followed, including the replace/keep/merge choice.

**Non-Goals:**
- Optimistic updates, an offline queue, or editing a cart while Customer Service is down.
- "Add to cart" on the list tiles: the list mockup has none. The scope line of the iteration plan ("add from list/details") is narrowed to the details page.
- Checkout. The checkout button is a stub, replaced in iteration 3.

## Decisions

### D1. One cart interface, two sources
A `CartProvider` sits inside `SessionProvider`, around the layout, and exposes:

```
owner: 'guest' | 'customer' | 'none'
lines, status
add(productId, qty), setQuantity(productId, qty), remove(productId), clear()
```

- `guest`: a visitor (no session). The source is the guest store (D2).
- `customer`: a `CUSTOMER` session. The source is the server cart (D4).
- `none`: staff. No cart; the actions are unavailable, and the guest store is neither read nor written.

The header, the product page and the cart page use only this interface.

*Alternative:* the guest cart as a fake "server" behind the same API functions. Rejected: it would hide the difference the login rule depends on, and it would make the mock and the real client harder to tell apart.

### D2. Guest store and shared rules
`src/features/cart/cart-rules.ts` holds pure functions: `addItem`, `setItemQuantity`, `removeItem`. Each returns `{ ok: true, items }` or `{ ok: false, violation }`.
- The functions check INV-CART-01/02 on the result, as Customer Service does, and keep the items ordered by `productId` like the server's answer.
- The guest store (`guest-cart.ts`) keeps `{ version: 1, items }` in `localStorage` under `voltstore.guestCart`.
  - Reading validates every entry and drops the whole value if it is not valid.
  - Writing notifies subscribers in this tab; a `storage` listener covers other tabs.
  - The store is read through `useSyncExternalStore`.
- One table, `CART_MESSAGES`, maps a violation code to the user message, for both the guest cart and the server's `422` (spec *Cart limits*). `DUPLICATE_PRODUCT` cannot happen from this client, because the guest store holds each product once; it maps to the generic unexpected message.
- The customer's cart is **not** pre-checked against the cached cart: another device may have changed it, and the server is the authority.

### D3. API layer: `src/api/customer.ts`
- Types and calls for EP-CUST-03…09: `getCart`, `addCartItem`, `setCartItemQuantity`, `removeCartItem`, `replaceCart`, `mergeCart`, `clearCart`. All return `CartItem[]`.
- `cartViolation(error)` reads `violation` from an `ApiError` of kind `unprocessable`.
- The profile calls (EP-CUST-01/02) wait for iteration 3.

### D4. Server cart with TanStack Query
- **Query.** Key `['cart']`. It is enabled only while the owner is `customer`. Logout already clears the query cache (foundation D5).
  - It is refetched whenever the cart page mounts (`refetchOnMount: 'always'`) and on window focus, so changes from other devices appear (spec *Cart opened on a second device*).
  - It overrides the app default `refetchOnWindowFocus: false` for this one query.
- **Mutations.**
  - Every mutation writes its answer into the query with `setQueryData(['cart'], answer)`.
  - All cart mutations share the mutation scope `{ id: 'cart' }`, so TanStack Query runs them one after another. Two quick changes therefore cannot have their answers arrive out of order and overwrite a newer cart with an older one.
  - The line being changed is disabled until its answer arrives (spec *Server cart follows the server*).
- **Errors.**
  - `422` → message from `CART_MESSAGES`, then invalidate `['cart']`.
  - `404` on `PATCH` → "This product is no longer in your cart.", then invalidate.
  - `503` or network → the existing unavailable message; nothing is resent (foundation D3).
  - `401` → the existing session-expired path.
- *Alternative:* optimistic updates with rollback. Rejected: answers are fast, and showing only the server's answer keeps the screen correct at no cost.

### D5. Line data and the subtotal
- **Line data.**
  - The cart page runs `useQueries` over the lines with the existing `catalogKeys.product(id)` and `getProduct` (no token). Product data is therefore shared with the product page's cache.
  - A `404` makes the line unavailable with the title "Product no longer available".
  - `OUT_OF_STOCK` makes the line unavailable with its title kept.
  - Any other error gives the line a "Try again" (spec *Product details cannot be loaded*).
- **Subtotal.**
  - The estimated subtotal and line totals are computed in the client in whole kopecks: `Math.round(price × 100) × quantity`, summed, then divided by 100 for `Price`. This avoids floating-point drift.
  - This is the one place where the client does arithmetic on prices. Foundation D9 said there would be none; the cart mockup asks for an *estimated* subtotal, and checkout totals still come from Order Service (iteration 3).
- **Header badge.** It counts the quantities of all lines from the cart alone, without product data, so every page does not trigger catalog requests. On the cart page, by contrast, the item count leaves out unavailable lines (spec *Cart summary*).

### D6. Guest cart on login: the resolver
A `GuestCartResolver` inside `CartProvider` runs the rule of the Customer Service spec when the owner is `customer` and the guest cart is not empty.

**When it starts.** `SessionProvider` gains a `source` field: `'login' | 'stored' | 'other-tab'`. The resolver starts for `login` (login or registration in this tab) and `stored` (application load). It does not start for `other-tab`, so two tabs do not both apply one guest cart. The tab that resolves empties the guest cart, and the `storage` event updates the others.

**Steps.** The resolver is a small state machine:

```
reading  →  GET /customers/cart
         →  account empty     →  replacing (PUT /customers/cart with the guest items)  →  done
         →  account has lines →  asking (dialog)
asking   →  replace (PUT) | keep (no request; the GET answer is used) | merge (POST /customers/cart/merge)  →  done
done     →  write the answer into ['cart'], empty the guest cart
error    →  guest cart kept; "Try again" restarts at reading
```

**Dialog.** A Mantine `Modal` with no close button that ignores Escape and outside clicks: it stays open until a choice succeeds (spec *Replace, keep or merge choice*).
- It lists both carts with titles, using the product queries of D5, and quantities.
- A `422` keeps the dialog open with the message.
- A network error or `503` from a choice returns to `reading`. The account cart is read again before the customer can try again, because a merge whose answer was lost may already have been applied.

**Guard.** A ref-held state prevents a second start while one run is in progress, including the double effects of React StrictMode.

### D7. Route and navigation
- **`/cart` route.** `CartPage` sits under the layout, outside every `RequireRole`. Staff get the existing `AccessDeniedPage`, and no cart query runs, because the owner is `none`.
- **Return page after login.** `landingAfterLogin` treats `/cart` as a customer page. A staff member who logs in from "Proceed to checkout" therefore lands in their own area, not on access-denied. This follows the existing scenario *Return page not allowed for the role*.
- **Header.** A cart icon button (`IconShoppingCart`) with a `volt` count badge (design system: "the cart count badge"), shown when the owner is `guest` or `customer`.
  - On desktop it sits before the user block.
  - On phones it sits next to the colour-scheme toggle, outside the burger menu.
- **`/account` placeholder.** Its text no longer promises the cart.

### D8. Product page
- A Mantine `NumberInput` (1–10) next to an "Add to cart" button, as in `Product.dc.html`.
- The button is disabled for `OUT_OF_STOCK`, and while a customer's add is in progress.
- On success, a notification "Added to cart" with a link to `/cart`. A limit or service error appears inline under the button.
- When the owner is `none`, nothing is rendered.

### D9. Checkout stub
- For a customer, "Proceed to checkout" is disabled, with the note "Checkout arrives in the next iteration."
- For a visitor, it links to `loginUrl('/cart')`.
- Iteration 3 replaces this with the real checkout route.

### D10. Mock backend, fixtures and seed
- **Mock database.** `MockDb` gains `carts: Map<userId, CartItem[]>`.
- **Handlers.**
  - `src/mocks/handlers.ts` gains the seven routes with `authenticate(request, { required: ['CUSTOMER'] })` (`401`/`403`).
  - Validation follows the spec: `400` with `errors` for a missing or non-UUID `productId` and for a missing, decimal or below-1 `quantity`; a non-UUID path id gets the gateway's `404`.
  - `422` violations reuse `cart-rules.ts`. `DUPLICATE_PRODUCT` is checked before the rules.
  - `PATCH` of a missing line answers `404`; every answer is sorted by `productId`.
- **Demo cart fixture.** `DEMO_CART` in `src/fixtures/demo-data.ts`, for the demo customer:
  - 1 of an in-stock product;
  - 2 of a second in-stock product;
  - 1 of an inactive product.

  It is referenced by fixture title, so the mock and the seed resolve the same products. The mock starts the demo customer's cart from it. The cart page then shows the mockup's unavailable line, and a guest who logs in as the demo customer meets the replace/keep/merge dialog at once.
- **Seed.** A new step after activation:
  - log in as the demo customer and `GET /customers/cart`;
  - if the cart is empty, `PUT` the demo cart, with ids resolved by title from the manager's product list; otherwise leave it.

  The summary reports `demo cart: set` or `kept`. A second run stays `unchanged`. Customer Service doesn't check products, so the inactive line can be stored.

### D11. Tests
- **Unit tests:**
  - `cart-rules`: each limit, checked on the result;
  - guest store: reading invalid data, `storage` sync;
  - subtotal in kopecks;
  - `cartViolation`.
- **Component tests**, through `renderApp` and the mock handlers, named after the spec scenarios. They cover:
  - the cart page (lines, unavailable lines, summary, stepper, remove, clear, the empty cart, a product that fails to load);
  - the product page add;
  - the header badge;
  - every resolver path (empty account cart, empty guest cart, merge, keep, replace, `422` during merge, `503` while reading);
  - staff denial;
  - the `404` on `PATCH`.
- **Playwright journeys** for the mock and live projects:
  1. A visitor adds two products, sees the badge and the cart page, reloads, and the cart is kept.
  2. The visitor logs in as the demo customer, chooses "Merge both", and sees the summed cart. Logging out leaves an empty cart.
- **Live data reset.** Before the live journeys, a support helper logs in as the demo customer through the gateway and `PUT`s the demo cart, so earlier runs don't change the result.

## Risks / Trade-offs

- [A merge whose answer is lost and is then retried doubles quantities (the spec accepts this)] → the resolver reads the account cart again after a failed merge before offering a choice; the invariants cap the result.
- [Cart lines show product data up to 30 s old (query `staleTime`)] → the cart page refetches the cart on mount; product staleness is acceptable, because the subtotal is labelled "estimated" and checkout re-checks prices.
- [Two tabs of one customer: a change in one tab is not pushed to the other] → the cart query refetches on window focus and on opening the cart page; no push channel exists (BL-13 covers push updates for orders only).
- [The session expires while the dialog is open] → the session-expired path clears the query cache. The guest cart is still in the browser, so the rule runs again after the next login.
- [`/cart` is not a protected path, so an idle expiry on the cart page does not redirect] → the page falls back to the (empty) guest cart. Accepted: the server cart is safe, and logging in again shows it.
- [10 product requests on a full cart] → acceptable at this scale; replace them with a public batch read if Catalog ever offers one (not needed as a backlog item now).

## Migration Plan

New frontend code only. The `voltstore.guestCart` key is new, so there is nothing to migrate. Rollback means reverting this change's commits.
