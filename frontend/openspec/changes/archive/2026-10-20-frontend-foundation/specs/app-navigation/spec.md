# Spec Delta

## Purpose

Defines the application shell shared by all pages: the URL areas per role, what each role sees in the navigation, how protected pages are guarded, and the fallback pages for unknown URLs and missing access.

## ADDED Requirements

### Requirement: URL areas per role
The application SHALL organize its pages into areas by URL prefix. The storefront (`/`, `/products/...`, `/login`, `/register`) is open to everyone. `/account/...` requires `CUSTOMER`, `/manage/...` requires `CATALOG_MANAGER`, `/warehouse/...` requires `INVENTORY_WORKER`, and `/admin/...` requires `ADMIN`. Roles are not hierarchical: `ADMIN` has no access to the other protected areas.

#### Scenario: Admin cannot open the manager area
- **WHEN** a user with role `ADMIN` opens a `/manage/...` URL
- **THEN** the access-denied page is shown

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
The header SHALL show navigation that matches the current user: visitors see the catalog, "Log in" and "Register"; each logged-in role sees links to the pages of its own area that exist, plus "Log out". Links to areas the user cannot open SHALL NOT be shown.

#### Scenario: Visitor header
- **WHEN** no one is logged in
- **THEN** the header shows the catalog link, "Log in" and "Register", and no links to protected areas

#### Scenario: Manager header
- **WHEN** a catalog manager is logged in
- **THEN** the header shows links to the manager area and "Log out", and no links to the customer, warehouse or admin areas

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
