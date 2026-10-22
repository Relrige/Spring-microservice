# Design

## Context

- Iterations 1, 1a and 2 are archived (`frontend-foundation`, `api-contract-types`, `customer-cart`). Their decisions hold and are referred to as *foundation D1–D16* and *cart D1–D11*:
  - one API layer over the generated types (`AGENTS.md` §3);
  - TanStack Query, with mutations never retried;
  - the session with `source`;
  - the `CartProvider` with the server cart in the `['cart']` query;
  - the MSW mock shared by every test level;
  - demo fixtures shared by the mock and the seed.
- **Order Service contract:** `../docs/design/services-specs/order-service-spec.md` and `order-service/openapi/public.json`, generated into `src/api/generated/order.ts`.
  - operationIds: `validate` (`204`), `place` (`201`, optional `Idempotency-Key` header), `pay`, `history`, `details`, `cancel` (`202`).
  - Problem kinds by `type`: `ineligible-items`, `insufficient-stock` and `order-total-too-large` (all `422`); `idempotency-key-mismatch` (`422`); `request-in-progress` (`409` with `Retry-After`); `payment-declined` (`402` with `reason`); `payment-window-expired` (`409`); and `conflict` (`409`).
  - A `conflict` on an order state carries `orderStatus` (`OrderStateProblem`); the "payment attempt in progress" `conflict` does not.
- **Payment** is reached only through Order (`pay`). Its demo provider decides by the card's last four digits: `4242` approves, `0002` declines with insufficient funds, `5000` approves after a lost answer (about 2.5 s), anything else declines. Card rules: number 13–19 digits; month 1–12; year 2000–2099, not before the current month; CVV 3–4 digits.
- **Customer Service:** profile EP-CUST-01/02, with phone `^\+[1-9]\d{7,14}$`, names at most 100 characters and address at most 500. After `OrderPaid` it removes the ordered products from the cart asynchronously (EP-CUST-EVT-01).
- **Catalog drift:** Catalog tasks 27–29 changed `catalog-service/openapi/public.json`, so `npm run check:api` fails on the current branch.
  - EP-CAT-05 now answers `ProductResponse`; `ProductDetailsResponse` is gone.
  - Listing has an `ids` filter, and staff see inactive products.
  - The storefront sends no token, so what it shows is unchanged.
- **Mockup:** `docs/design/screens/Checkout.dc.html`, the payment step. It shows the step indicator, the order with its pay-by time, the delivery details, the card form with a declined message and the test cards, the order summary, and "Cancel order". There are no mockups for the delivery step, the order pages or the profile; they follow the design system README and the cart page.
- **Backlog:** BL-07 is delivered on the backend (status `In progress` until verified from the frontend). BL-11 and BL-13 are `Open` and get workarounds.

## Goals / Non-Goals

**Goals:**
- One checkout flow that cannot place two orders for one attempt, even after a lost answer (BL-07).
- The order on screen never drifts from Order Service: every change shows the server's answer, and orders that can still change are read again (BL-13 polling).
- Card data exists only in the payment form's memory.

**Non-Goals:**
- A checkout for a subset of the cart: the whole cart is ordered.
- Editing an order after it is placed, or choosing the delivery branch from a carrier list (BL-11).
- Showing order emails; Notification Service sends them, and Mailpit shows them in the stack.

## Decisions

### D1. Catalog types first
- Run `npm run gen:api`, review the Catalog diff, then point `ProductDetails` at `Received<Schemas['ProductResponse']>`.
- Adapt the EP-CAT-05 mock handler: its answer `satisfies` the new schema, and it stays `404` for inactive products when called without a staff token.
- The new `ids` filter and the staff visibility are not used in this iteration; they belong to iterations 4 and 5.
- This is the change's first task, so every later commit starts from a green `npm run check`.

### D2. API layer
- **`src/api/order.ts`:** `validateCheckout`, `placeOrder(body, idempotencyKey)`, `payOrder`, `listOrders`, `getOrder` and `cancelOrder`, typed by operationId (`AGENTS.md` §3).
  - Short aliases: `OrderStatus`, `OrderSummary`, `OrderDetails`, `PlacedOrder`, `DeliveryDetails`, `CardDetails`.
  - `orderProblem(error)` returns one tagged result per problem kind, read with `problemOf` on the `type`. The `conflict` case is split by whether `orderStatus` is present: an order-state problem or a payment in progress.
- **`src/api/customer.ts`** gains `getProfile` and `saveProfile` (EP-CUST-01/02) with `Profile = Received<Schemas['ProfileResponse']>`.
- **`request()`** gains an optional `headers` option, used only for `Idempotency-Key`. The answer's `Idempotent-Replayed` header is not read: a replay is handled like any `201`.

### D3. Routes and navigation
- **Routes**, all behind `RequireRole allow="CUSTOMER"`:
  - `/checkout`: `CheckoutPage`, the delivery step;
  - `/checkout/:orderId`: `PaymentPage`;
  - `/account`: redirects to `/account/orders`;
  - `/account/orders`: `OrderHistoryPage`;
  - `/account/orders/:orderId`: `OrderPage`;
  - `/account/profile`: `ProfilePage`.

  The `/account` placeholder is removed.
- **`AREAS`** gains `{ prefix: '/checkout', role: 'CUSTOMER' }`, so the login return page and idle expiry treat checkout as protected.
- **Customer header links:** "Orders" (`/account/orders`) and "Profile" (`/account/profile`). The checkout step indicator ("1. Delivery details", "2. Payment") is page content, not navigation.

### D4. Checkout entry from the cart
- **Before the check:** the customer's "Proceed to checkout" is a button, not a link. It is disabled while any line view is `gone`, `sold-out`, `failed` or `loading`, with the note "Remove unavailable products to check out."
- **The check:** it runs a `validateCheckout` mutation with every cart line and navigates to `/checkout` on success.
- **`422` answer:** the cart page shows one alert listing the products by title (from the product queries), each with "no longer available" or "out of stock". It then invalidates those products' catalog queries, so the lines re-render as unavailable.
- **Other errors** show `userMessage`.
- **Opening `/checkout` directly** (bookmark, reload) skips the soft check. The submission checks again anyway (EP-ORD-02), so the page loads straight away.

### D5. Delivery step
- **The order and its total:** the checkout page orders `cart.lines`, the server cart. It shows them with the product queries and estimated subtotal of cart D5 (the line-view hook is extracted from `CartPage` and shared).
- **The form:** a Mantine form with `recipient`, `phone`, `address` and `carrier`.
  - The phone accepts spaces while typing; spaces are removed before checking against `^\+[1-9]\d{7,14}$` and before sending.
  - `carrier` is a `Select` with the single option `NOVA_POSHTA` labelled "Nova Poshta" (`// BL-11: carriers hardcoded until Order lists them`).
- **Pre-fill:** from the `['profile']` query, once, and only fields the customer has not touched yet. A failed profile read leaves the form empty.
- **"Save these details to my profile":** checked by default when the profile has no saved values (every field `null`).
  - On `201`, if checked, `saveProfile` runs in the background: first name is the recipient up to the first space, last name the rest, or `-` when there is no space, since the service requires a non-blank last name.
  - Its failure is logged and ignored (spec *Profile save fails*).
- **An empty cart** shows the cart's empty state.

### D6. Idempotency key per attempt
- The page keeps `attempt = { fingerprint, key }` in a ref. `fingerprint` is the JSON of the cart lines and the normalized delivery values.
- **On submit:** when the fingerprint differs from the attempt's, a new `crypto.randomUUID()` key is made; otherwise the key is reused. A `422` `idempotency-key-mismatch` clears the attempt.
- **`409` `request-in-progress`:** the page waits `retryAfter` seconds (default 1) and sends the same request with the same key once more. If that is also in progress, it shows the unavailable message and sends nothing more.
- **On `201`:** `navigate('/checkout/' + orderId, { replace: true })` and invalidate `['orders']`. The cart is not touched; Customer Service clears it after the payment.
- *Alternative:* a key stored in `sessionStorage` to survive a reload. Rejected: after a reload the customer sees the new order in the history (the order list is the source of truth), and a stored key would outlive cart edits made in other tabs.

### D7. Payment step
- **The order:** `PaymentPage` reads the order with `getOrder`, shares the `['orders', 'detail', id]` query with the order page, and renders the mockup's layout.
- **Countdown:** a `useNow(1000)` tick computes the minutes left from `reservationExpiresAt`. When it passes, the form is replaced by the expired notice; the server is still the authority (`409`).
- **Card form:**
  - Number with spaces allowed, then 13–19 digits.
  - "MM/YY" parsed to `expiryMonth` and `2000 + YY`, rejected when before the current month in local time.
  - CVV of 3–4 digits.
  - Inputs use `autocomplete="cc-number" | "cc-exp" | "cc-csc"`.
  - The values live only in form state: they never go into a query key, the cache, the URL or storage, and the form is reset after approval.
- **On approval:**
  - set the detail query to the answer's status and invalidate `['orders']`;
  - invalidate `['cart']` now and once more after 3 s, since Customer Service clears the cart asynchronously;
  - show a "Payment received" notification and navigate to `/account/orders/:orderId`.
- **Failures** map `orderProblem` kinds to the messages of the spec. A `402` message names the reason: `insufficient_funds` reads "insufficient funds", anything else "declined".

### D8. Order queries and polling
- **Keys:** `['orders', 'list', page]` and `['orders', 'detail', orderId]`.
- **Polling:** `refetchInterval` returns `5000` while the shown order, or any row on the list page, is in a status that can still change, and `false` otherwise.
  - TanStack Query does not poll hidden tabs by default (`refetchIntervalInBackground: false`).
  - Marked `// BL-13: polling until Order pushes status changes`.
- **Display:** `ORDER_STATUS` and `DELIVERY_STATUS` tables map each status to its label and `StatusChip` tone (design system README). `IN_TRANSIT`, `DELIVERED` and `CANCELLED` delivery statuses, which have no README entry, read "In transit", "Delivered" and "Cancelled" in the `primary`, `success` and `danger` tones.
- **New helpers in `src/components/`:**
  - `formatDateTime` (`18 Oct 2026, 14:05`, `en-GB`, local time) and `formatTime` (`14:20`);
  - `ShortId` (`#7f3a91c2` in the `mono` style, with the full id in `title`).

### D9. Cancellation
- **Control:** "Cancel order" appears on the order page and on the payment step for `PENDING_PAYMENT`, `PAID` and `PENDING_SHIPPING`.
- **Confirmation:** a `Modal` whose text adds "The payment will be refunded." for paid orders.
- **Answers:**
  - `202`: the detail query gets the answer's status, and `['orders']` is invalidated. `CANCELLATION_REQUESTED` keeps polling (D8) until `CANCELLED`.
  - `409` with `orderStatus`: "This order can no longer be cancelled", then the order is read again.
- **From the payment step:** a successful cancel navigates to the order page.

### D10. Mock backend
- **`MockDb`** gains:
  - `profiles: Map<userId, Profile>`;
  - `orders: MockOrder[]`, with items, delivery, status, times, `ttn` and `deliveryStatus`;
  - `idempotencyKeys: Map<userId + key, { hash, orderId }>`.
- **Handlers** follow the Order spec, with every answer `satisfies` the generated schemas and every problem carrying its `type`:
  - **validate:** ineligible when a product is missing, inactive, or has no stock left.
  - **place:**
    1. checks `400` first, then the `Idempotency-Key`: a replay answers `201` with `Idempotent-Replayed: true`, a mismatch answers `422`;
    2. then ineligible items and insufficient stock against `MockProduct.available`, then the total limit;
    3. finally reserves (decrements `available` and recomputes `stockStatus`) and creates the order with a 15-minute window.

    `409` `request-in-progress` cannot happen in a synchronous mock; tests override the handler to produce it.
  - **pay:** card rules (`400`); the `404`/`409`/expired checks; then the outcome by the last four digits. `5000` approves after a 2.5 s delay (`delay()`, set to 0 in unit tests through a mock setting). An approval sets `PAID` and `paidAt` and removes the ordered products from the customer's cart.
  - **history:** `page`/`size` validated, newest first.
  - **cancel:** the allowed statuses become `CANCELLED` at once and release the stock. Other statuses answer `409` with `orderStatus`.
- **Lazy background progress**, applied when an order is read: a `PENDING_PAYMENT` order past its window (plus 30 s grace) becomes `EXPIRED` and releases its stock. A `PAID` order paid 3 s ago or more becomes `PENDING_SHIPPING`, with TTN `2045` plus 10 digits and `deliveryStatus` `TTN_GENERATED`. This shows polling without timers and is testable with `vi.setSystemTime`.
- **Problem helper:** the `problem()` helper of the mock gains an optional `headers` argument for `Retry-After` and `Idempotent-Replayed`.

### D11. Demo profile and seed
- **`DEMO_PROFILE`** in `src/fixtures/demo-data.ts`: Olena Kovalenko, `+380501234567`, "Kyiv, branch No. 12, Velyka Vasylkivska St 72". This is the mockup's recipient.
- **Mock:** it starts the demo customer's profile from `DEMO_PROFILE`.
- **Seed:** a step after the demo cart. When the demo customer's profile has every field `null`, it `PUT`s the demo profile; otherwise it leaves it. The summary gains `demo profile: set | kept`, and a set counts as a change.

### D12. Tests
- **Unit tests:**
  - `orderProblem` for each kind, including the two `conflict` cases;
  - the attempt-key logic (same fingerprint reuses, a change renews);
  - card expiry parsing and the "not before this month" rule;
  - `formatDateTime`;
  - the status tables cover every generated status value (a type test pins them to the generated enums).
- **Mock handler tests:** every Order route and status code, through the API calls.
- **Component tests:** named after the spec scenarios, through `renderApp` and the mock. Polling is tested with fake timers.
- **Playwright journeys** (mock and live):
  1. **Checkout:**
     - log in as the demo customer;
     - the cart shows the demo cart's unavailable line and a disabled checkout;
     - remove it and proceed;
     - the delivery form is pre-filled from the demo profile; place the order;
     - pay with `…0002` and see the decline;
     - pay with `…4242`;
     - the order page shows "Paid" or "Ready to ship";
     - the history lists the order.
  2. **Cancel:** place an order, open it from the history, cancel it and see "Cancelled".

  In live mode, the existing cart reset runs before each journey. The demo profile is left as seeded.

## Risks / Trade-offs

- **[Customer Service removes ordered lines asynchronously]** → The cart is read again right after the payment and once more after 3 s, and again on focus and on opening the cart. A line may linger for a moment, which the spec allows.
- **[Client clock differs from the server's]** → The countdown is advisory, and the server decides with `409` `payment-window-expired`. The memory of earlier runs notes clock drift in minikube. Compose uses the host clock.
- **[Polling every 5 s per open order page]** → Only visible tabs poll, only non-final orders, and one request per page. This is acceptable for the demo until BL-13.
- **[Live journeys consume stock]** → Paid orders reduce stock, and cancelled or expired ones release it. `npm run seed` adjusts stock back to the fixture targets, and the journeys order 1 unit of products with ample stock.
- **[Order Service does not start on the local stack]** → Its database volume holds an older schema. The live-run task resets the order database volume, after a person approves it, before running the journeys.
- **[A reload during submission can still create two orders]** → The key is not persisted (D6). The second order expires unpaid after 15 minutes and releases its stock, which is the accepted behaviour without a key (EP-ORD-02).

## Migration Plan

New frontend code and regenerated Catalog types; no data to migrate. Rollback means reverting this change's commits. BL-07 moves to `Done` in `docs/backlog/` only after the live run shows a replayed submit.
