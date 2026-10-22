# Tasks

Apply after `api-contract-types`: `src/api/generated/customer.ts`, `src/api/contract.ts` and `problemOf` must exist first.

## 1. Cart contract and mock backend

- [x] 1.1 Write `src/api/customer.ts` on `generated/customer.ts` (design D3): `CartItem` as `Received<CartItemResponse>`, the seven cart calls of EP-CUST-03…09 typed by operationId with `JsonBody`/`Ok` and `pathFor`, each citing its EP number and operationId, and `cartViolation(error)` on `problemOf` with `cart-rule-violation`; verify with type tests that `CartItem` and the request bodies match the generated types, and unit tests that `cartViolation` reads each `violation` code from a `cart-rule-violation` problem and returns nothing for a `422` with another `type` and for other errors
- [x] 1.2 Write `src/features/cart/cart-rules.ts` and `CART_MESSAGES` keyed by the generated `CartViolation` (design D2): add, set and remove with INV-CART-01/02 checked on the result and items ordered by `productId`; verify with unit tests for the 11th product, 8 + 3 of one product, quantity 0 and 11, and adding to an existing line
- [x] 1.3 Add mock carts and handlers for the seven routes (design D10): `CUSTOMER` only, `400` field errors, `422` violations from `cart-rules`, `DUPLICATE_PRODUCT` before the rules, `404` only for `PATCH` of a missing line, idempotent deletes, answers ordered by `productId` and typed with `satisfies` against the generated types, `422` problems with the `cart-rule-violation` type, mock db reset per test; verify with handler tests through the API calls, including the `type` URI of a `422`, including a staff token (`403`) and no token (`401`)
- [x] 1.4 Add `DEMO_CART` to `src/fixtures/demo-data.ts` (1 and 2 of two in-stock products, 1 of an inactive product, referenced by title) and start the demo customer's mock cart from it; verify with a handler test that the demo customer's `GET /customers/cart` returns the three lines

## 2. Cart state (spec `shopping-cart`)

- [x] 2.1 Implement the guest store `guest-cart.ts` (design D2): `voltstore.guestCart` with version and validation, subscribe and notify, `storage` sync; verify with unit tests for *Reload keeps the guest cart*, *Change in another tab* and *Unreadable stored cart*
- [x] 2.2 Add `source` (`login | stored | other-tab`) to `SessionProvider` (design D6); verify with session tests that login, reload and a login in another tab report the right source, and that the existing session tests still pass
- [x] 2.3 Implement `CartProvider` with the owner by role, the guest source and the server source (query `['cart']` with refetch on mount and focus, mutations in scope `cart` writing the answer into the query, per-line pending state, error handling of design D4), and mount it inside `SessionProvider`; verify with tests for *Visitor adds a product*, *Customer adds a product*, *Staff login keeps the guest cart*, *Server rejects a change*, *Line removed on another device* and *Customer Service unavailable*

## 3. Cart page, header and product page

- [x] 3.1 Add the `/cart` route with staff denial, and make `landingAfterLogin` treat `/cart` as a customer page (design D7); verify with navigation tests for *Manager opens the cart*, *Visitor opens the cart* and a staff login with `returnTo=/cart` landing in the staff area
- [x] 3.2 Build the cart page as in `Cart.dc.html`: lines with live product data (design D5), unavailable lines, product-load failure with "Try again", stepper and remove, clear with confirmation, empty state, loading and error states; verify with component tests for *Cart with two products*, *Product details cannot be loaded*, *Product deactivated after it was added*, *Product sold out after it was added*, *Increase a quantity*, *Stepper bounds*, *Remove a line*, *Clear after confirming*, *Clear cancelled* and *New visitor opens the cart*
- [x] 3.3 Add the summary with item count, estimated subtotal in kopecks, the incomplete-subtotal note and the checkout stub (design D5, D9); verify with a unit test for the kopeck sum and component tests for *Summary with an unavailable line* and *Visitor proceeds to checkout*
- [x] 3.4 Add the header cart control with the volt count badge for visitors and customers, outside the burger menu on phones (design D7), and update the `/account` placeholder text; verify with component tests for *Count after adding*, *Visitor header*, *Manager header* and *Customer header on a phone*
- [ ] 3.5 Add the quantity selector and "Add to cart" to the product details page (design D8); verify with component tests for *Visitor adds two*, *Out-of-stock product*, *Manager sees no cart action* and *Add is rejected by a limit*

## 4. Guest cart on login

- [ ] 4.1 Implement the guest-cart resolver state machine with its start rules and guard (design D6), including the silent replace and the emptying of the guest cart; verify with tests for *Empty account cart*, *Empty guest cart*, *Logout after merge*, *Customer Service down at login*, no start for a session from another tab, and a single `PUT` under StrictMode
- [ ] 4.2 Build the replace/keep/merge dialog (no close button, Escape and outside clicks ignored, both carts listed with titles, inline `422` message, re-read after a failed choice); verify with component tests for *Merge*, *Keep account cart*, *Merge breaks a limit* and replace

## 5. Demo data, browser journeys and docs

- [ ] 5.1 Add the demo-cart step to `scripts/seed.ts` (design D10: set only when the demo customer's cart is empty; summary `demo cart: set | kept`); verify against the Compose stack that the first run sets the cart, a second run reports `Result: unchanged`, and the demo customer's cart page shows two lines and one unavailable line
- [ ] 5.2 Write the cart journeys (design D11): guest cart with badge and reload; login as the demo customer with "Merge both" and logout to an empty cart; add the live-mode reset of the demo customer's cart to `e2e/support.ts`; verify `npm run check:e2e` passes
- [ ] 5.3 Run `npm run test:e2e:live` against the Compose stack after `npm run seed`, fix any mock mismatch found in the cart contract (a generated type that disagrees with the service spec is a backlog item, `AGENTS.md` §3, not a frontend fix), and record the result in the *Live verification* section of `frontend/README.md` together with the demo cart in *Demo accounts*; verify the live run passes
- [ ] 5.4 Check the finished screens against `Cart.dc.html` and `Product.dc.html` in the preview pane on desktop and at 375 px (light and dark), including the dialog; verify by screenshots that layout, hierarchy and colours match and there is no horizontal scroll

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive` so `shopping-cart` becomes a main spec and the `catalog-browsing` and `app-navigation` deltas are merged.
- Propose iteration 3 (`customer-checkout-orders`), which replaces the checkout stub.
