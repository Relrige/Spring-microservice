# app-navigation Specification

## Purpose

Defines the application shell shared by all pages: the URL areas per role, what each role sees in the navigation, how protected pages are guarded, and the fallback pages for unknown URLs and missing access.

## Requirements

### Requirement: URL areas per role
The application SHALL organize its pages into areas by URL prefix. The storefront (`/`, `/products/...`, `/login`, `/register`) is open to everyone. The cart `/cart` is open to visitors and customers; catalog managers, inventory workers and admins get the access-denied page there. `/account/...` and `/checkout/...` require `CUSTOMER`, `/manage/...` requires `CATALOG_MANAGER`, `/warehouse/...` requires `INVENTORY_WORKER`, and `/admin/...` requires `ADMIN`. `/account` itself leads to the order history, and `/manage` itself leads to the product list `/manage/products`. Roles are not hierarchical: `ADMIN` has no access to the other protected areas.

#### Scenario: Admin cannot open the manager area
- **WHEN** a user with role `ADMIN` opens a `/manage/...` URL
- **THEN** the access-denied page is shown

#### Scenario: Manager opens the cart
- **WHEN** a logged-in catalog manager opens `/cart`
- **THEN** the access-denied page is shown and no cart request is sent

#### Scenario: Visitor opens the cart
- **WHEN** a visitor who is not logged in opens `/cart`
- **THEN** the cart page is shown without asking to log in

#### Scenario: Worker opens the checkout
- **WHEN** a logged-in inventory worker opens `/checkout`
- **THEN** the access-denied page is shown and no order request is sent

#### Scenario: Customer opens the account
- **WHEN** a customer opens `/account`
- **THEN** the order history at `/account/orders` is shown

#### Scenario: Manager opens the manager area
- **WHEN** a catalog manager opens `/manage`
- **THEN** the product list at `/manage/products` is shown

#### Scenario: Unknown manager page
- **WHEN** a catalog manager opens `/manage/no-such-page`
- **THEN** the not-found page is shown

### Requirement: Landing area per role
After login, and when a logged-in user opens `/login` or `/register`, the frontend SHALL take the user to their role's landing area: `CUSTOMER` → storefront `/`, `CATALOG_MANAGER` → `/manage`, `INVENTORY_WORKER` → `/warehouse`, `ADMIN` → `/admin`.

#### Scenario: Worker logs in
- **WHEN** an inventory worker logs in without a kept return page
- **THEN** they land on `/warehouse`

### Requirement: Protected pages need a login
Opening a protected page without a session SHALL take the user to the login page and keep the requested URL as the return page. A logged-in user whose role does not match the area SHALL see the access-denied page, with a link to their own landing area.

#### Scenario: Anonymous visitor opens a protected page
- **WHEN** a visitor opens `/account/orders` without a session
- **THEN** they are taken to the login page, and after logging in as a customer they arrive at `/account/orders`

#### Scenario: Return page not allowed for the role
- **WHEN** the kept return page is `/warehouse` and the user logs in as a customer
- **THEN** the user lands on the storefront instead of the access-denied page

#### Scenario: Wrong role
- **WHEN** a logged-in customer opens `/admin`
- **THEN** the access-denied page is shown with a link back to the storefront

### Requirement: Role-aware navigation
The header SHALL show navigation that matches the current user: visitors see the catalog, the cart, "Log in" and "Register"; customers see the catalog, "Orders", "Profile", the cart and "Log out"; catalog managers see "Products", "Categories" and "Log out"; each other logged-in staff role sees links to the pages of its own area that exist, plus "Log out". An inventory worker's links are "Stock" and "Fulfillment queue", the latter with the number of orders waiting for dispatch. Staff see no cart. Links to areas the user cannot open SHALL NOT be shown. The cart control SHALL stay in the header on narrow screens.

#### Scenario: Visitor header
- **WHEN** no one is logged in
- **THEN** the header shows the catalog link, the cart, "Log in" and "Register", and no links to protected areas

#### Scenario: Manager header
- **WHEN** a catalog manager is logged in
- **THEN** the header shows "Products" linking to `/manage/products`, "Categories" linking to `/manage/categories` and "Log out", and no cart and no links to the customer, warehouse or admin areas

#### Scenario: Customer header on a phone
- **WHEN** a customer is logged in and the page is 375 px wide
- **THEN** the cart control with its count is visible in the header without opening the menu

#### Scenario: Customer header
- **WHEN** a customer is logged in
- **THEN** the header shows the catalog, "Orders" linking to `/account/orders`, "Profile" linking to `/account/profile`, the cart and "Log out"

#### Scenario: Worker header
- **WHEN** an inventory worker is logged in and 2 orders wait for dispatch
- **THEN** the header shows "Stock" linking to `/warehouse` and "Fulfillment queue" linking to `/warehouse/queue` with the count 2, and no cart

### Requirement: Not-found page
Any URL that matches no page SHALL show a not-found page with a link to the storefront, keeping the header and navigation.

#### Scenario: Unknown URL
- **WHEN** a user opens `/no-such-page`
- **THEN** a "Page not found" page with a link to the catalog is shown

### Requirement: Usable layout on desktop and phone
Every page SHALL be usable at viewport widths from 360 px to desktop without horizontal page scrolling; on narrow screens the navigation SHALL collapse into a menu button.

#### Scenario: Phone width
- **WHEN** the application is opened at 375 px width
- **THEN** the page fits the width and the navigation is reachable through a menu button
