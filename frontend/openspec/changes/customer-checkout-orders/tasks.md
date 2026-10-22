# Tasks

Apply on a branch that contains `customer-cart` and the backend's Catalog tasks 27–29 and Order tasks 19–20 (BL-07).

## 1. Contracts and mock backend

- [x] 1.1 Run `npm run gen:api`, review the Catalog diff, point `ProductDetails` at `ProductResponse` and adapt the EP-CAT-05 mock handler (design D1); verify `npm run check` passes again and the product and cart page tests are unchanged
- [x] 1.2 Write `src/api/order.ts` (six calls by operationId, aliases, `orderProblem`) and add `getProfile`/`saveProfile` to `src/api/customer.ts`, with the optional `headers` option of `request()` (design D2); verify with type tests for the aliases and request bodies, unit tests that `orderProblem` tells every kind apart (both `conflict` cases included), and an http test that `Idempotency-Key` is sent
- [x] 1.3 Add `formatDateTime`, `formatTime`, `ShortId` and the `ORDER_STATUS`/`DELIVERY_STATUS` tables (design D8); verify with unit tests for `18 Oct 2026, 14:05` and a type test that the tables cover every generated status value
- [x] 1.4 Add mock profiles, orders and idempotency keys with the handlers of design D10 (validate, place with key replay and mismatch, pay by test-card digits with the `5000` delay, history, details with lazy expiry and TTN, cancel with stock release, cart clearing on payment) and the profile handlers; verify with handler tests through the API calls for every status code and problem `type`, including `401`/`403`
- [x] 1.5 Add `DEMO_PROFILE` and start the demo customer's mock profile from it (design D11); verify with a handler test that the demo customer's `GET /customers/me/profile` returns it and a new customer gets every field `null`

## 2. Routes, navigation and profile

- [x] 2.1 Add the routes of design D3, `/checkout` in `AREAS`, the `/account` redirect and the customer header links "Orders" and "Profile"; verify with navigation tests for *Worker opens the checkout*, *Customer opens the account*, *Customer header* and *Visitor opens the checkout*, and that the existing navigation tests pass
- [x] 2.2 Build `ProfilePage` (load, empty profile, client rules, save with phone normalization, server field errors); verify with component tests for *Saved profile is shown*, *No profile yet*, *Profile saved*, *Invalid phone* and *Server field errors*

## 3. Checkout

- [x] 3.1 Extract the line-view hook from `CartPage` and change the cart's checkout entry for customers (soft check mutation, disabled with unavailable or unloaded lines, `422` alert by product title) (design D4); verify with tests for *Customer proceeds to checkout*, *Unavailable line blocks checkout*, *Product became unavailable*, *Order Service unavailable at the check* and that the existing cart page tests pass
- [ ] 3.2 Build the delivery step: lines and estimated total, form with BL-11 carrier, profile pre-fill, save-to-profile option, empty cart (design D5); verify with tests for *Pre-filled checkout*, *Profile unavailable*, *Missing recipient*, *Carrier choice*, *Empty cart*, *Details saved with the order* and *Profile save fails*
- [ ] 3.3 Add the submission with the attempt key and the problem handling (design D6); verify with tests for *Order placed*, *Retry after an unavailable service*, *Details changed before a retry*, *Replayed answer*, *Not enough stock*, *Product no longer available*, *Order total too large*, *Key used for other details*, *Service unavailable at submission* and *Submission still running*
- [ ] 3.4 Build the payment step as in `Checkout.dc.html`: order summary, pay-by countdown, card form with client rules, test cards, outcomes, cancel link (design D7); verify with tests for *Order awaiting payment*, *Order already paid*, *Unknown order*, *Expired card*, *Test cards shown*, *Card declined*, *Approved payment*, *Payment in progress*, *Payment window elapsed*, *Payment service unavailable*, *Window passes while the page is open* and *Ordered products leave the cart*, and that no card value appears in `localStorage`, `sessionStorage` or the query cache

## 4. Orders

- [ ] 4.1 Build `OrderHistoryPage` (paged list, status chips, empty state, polling of changing rows) (design D8); verify with tests for *Orders listed*, *No orders* and *Status chip*
- [ ] 4.2 Build `OrderPage` (items, totals, delivery, TTN, pay-now link, missing delivery data, not found) with polling (design D8); verify with tests for *Paid order with a TTN*, *Order of another customer or unknown*, *Delivery data missing*, *Unpaid order*, *TTN arrives* and *Final status* (fake timers)
- [ ] 4.3 Add cancellation with confirmation on the order page and the payment step (design D9); verify with tests for *Cancel an unpaid order*, *Cancellation still running*, *Cancel not possible any more* and *Shipped order*

## 5. Demo data, journeys and docs

- [ ] 5.1 Add the demo-profile step to `scripts/seed.ts` (`demo profile: set | kept`, design D11); verify against the Compose stack that the first run sets the profile and a second run reports `Result: unchanged`
- [ ] 5.2 Write the checkout and cancel journeys (design D12); verify `npm run check:e2e` passes
- [ ] 5.3 With the Order Service database volume reset (ask a person first) and the stack seeded, run `npm run test:e2e:live`; check a repeated submit with the same key answers the first order (replay); fix any mock mismatch (a generated type that disagrees with a service spec is a backlog item, `AGENTS.md` §3); record the result in *Live verification* and the demo profile in *Demo accounts* of `frontend/README.md`; set BL-07 to `Done` in its file and the backlog index; verify the live run passes
- [ ] 5.4 Check the profile, delivery step, payment step, order history and order page in the preview pane on desktop and at 375 px (light and dark) against `Checkout.dc.html` and the design system README; verify by screenshots that layout and colours match and there is no horizontal scroll

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive` so `customer-profile`, `checkout` and `customer-orders` become main specs and the `shopping-cart` and `app-navigation` deltas are merged.
- Propose iteration 4 (`catalog-manager-console`) once BL-01 is `Done`.
