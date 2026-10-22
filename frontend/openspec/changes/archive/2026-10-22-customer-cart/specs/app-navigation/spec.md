# Spec Delta

## MODIFIED Requirements

### Requirement: URL areas per role
The application SHALL organize its pages into areas by URL prefix. The storefront (`/`, `/products/...`, `/login`, `/register`) is open to everyone. The cart `/cart` is open to visitors and customers; catalog managers, inventory workers and admins get the access-denied page there. `/account/...` requires `CUSTOMER`, `/manage/...` requires `CATALOG_MANAGER`, `/warehouse/...` requires `INVENTORY_WORKER`, and `/admin/...` requires `ADMIN`. Roles are not hierarchical: `ADMIN` has no access to the other protected areas.

#### Scenario: Admin cannot open the manager area
- **WHEN** a user with role `ADMIN` opens a `/manage/...` URL
- **THEN** the access-denied page is shown

#### Scenario: Manager opens the cart
- **WHEN** a logged-in catalog manager opens `/cart`
- **THEN** the access-denied page is shown and no cart request is sent

#### Scenario: Visitor opens the cart
- **WHEN** a visitor who is not logged in opens `/cart`
- **THEN** the cart page is shown without asking to log in

### Requirement: Role-aware navigation
The header SHALL show navigation that matches the current user: visitors see the catalog, the cart, "Log in" and "Register"; customers see the catalog, the cart and "Log out"; each logged-in staff role sees links to the pages of its own area that exist, plus "Log out", and no cart. Links to areas the user cannot open SHALL NOT be shown. The cart control SHALL stay in the header on narrow screens.

#### Scenario: Visitor header
- **WHEN** no one is logged in
- **THEN** the header shows the catalog link, the cart, "Log in" and "Register", and no links to protected areas

#### Scenario: Manager header
- **WHEN** a catalog manager is logged in
- **THEN** the header shows links to the manager area and "Log out", and no cart and no links to the customer, warehouse or admin areas

#### Scenario: Customer header on a phone
- **WHEN** a customer is logged in and the page is 375 px wide
- **THEN** the cart control with its count is visible in the header without opening the menu
