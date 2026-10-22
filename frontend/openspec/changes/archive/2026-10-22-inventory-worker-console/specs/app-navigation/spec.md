# Spec Delta

## MODIFIED Requirements

### Requirement: Role-aware navigation
The header SHALL show navigation that matches the current user: visitors see the catalog, the cart, "Log in" and "Register"; customers see the catalog, "Orders", "Profile", the cart and "Log out"; each logged-in staff role sees links to the pages of its own area that exist, plus "Log out", and no cart. An inventory worker's links are "Stock" and "Fulfillment queue", the latter with the number of orders waiting for dispatch. Links to areas the user cannot open SHALL NOT be shown. The cart control SHALL stay in the header on narrow screens.

#### Scenario: Visitor header
- **WHEN** no one is logged in
- **THEN** the header shows the catalog link, the cart, "Log in" and "Register", and no links to protected areas

#### Scenario: Manager header
- **WHEN** a catalog manager is logged in
- **THEN** the header shows links to the manager area and "Log out", and no cart and no links to the customer, warehouse or admin areas

#### Scenario: Customer header on a phone
- **WHEN** a customer is logged in and the page is 375 px wide
- **THEN** the cart control with its count is visible in the header without opening the menu

#### Scenario: Customer header
- **WHEN** a customer is logged in
- **THEN** the header shows the catalog, "Orders" linking to `/account/orders`, "Profile" linking to `/account/profile`, the cart and "Log out"

#### Scenario: Worker header
- **WHEN** an inventory worker is logged in and 2 orders wait for dispatch
- **THEN** the header shows "Stock" linking to `/warehouse` and "Fulfillment queue" linking to `/warehouse/queue` with the count 2, and no cart
