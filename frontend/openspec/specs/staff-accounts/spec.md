# staff-accounts Specification

## Purpose

The admin's page for creating staff and admin accounts at Auth Service (EP-AUTH-03), the only way to add catalog managers, inventory workers and further admins.

## Requirements

### Requirement: Create a staff account
`/admin` SHALL offer a form with email, password, password confirmation and role. The role SHALL offer Catalog manager, Inventory worker and Admin, and never Customer. Submitting SHALL send `POST /user/non-customer` with the trimmed email, the password and the role. On `201` the page SHALL confirm the account by email and role and clear the form.

#### Scenario: Worker account created
- **WHEN** the admin enters "new.worker@voltstore.com", a valid password twice and "Inventory worker", and submits
- **THEN** `POST /user/non-customer` is sent with role `INVENTORY_WORKER`, "Account created: new.worker@voltstore.com · Inventory worker" is shown and the form is empty

#### Scenario: No customer role
- **WHEN** the admin opens the role list
- **THEN** it offers Catalog manager, Inventory worker and Admin only

### Requirement: Account form rules
The form SHALL check before sending: email required and well formed, at most 256 characters; password 5 to 64 characters; the confirmation equal to the password; a role chosen. Nothing SHALL be sent while a rule is broken.

#### Scenario: Short password
- **WHEN** the admin enters a 4-character password and submits
- **THEN** no request is sent and the password field says to use 5 to 64 characters

#### Scenario: Passwords differ
- **WHEN** the confirmation differs from the password
- **THEN** no request is sent and the confirmation field says the passwords do not match

### Requirement: Rejected account
A `409` SHALL show "An account with this email already exists" on the email field and keep the entered values. A `400` SHALL show each field error on its field, and other errors as a general message. Nothing is resent automatically.

#### Scenario: Email in use
- **WHEN** the admin submits the email of an existing account and the answer is `409`
- **THEN** the email field shows "An account with this email already exists" and the role stays chosen

#### Scenario: Server field error
- **WHEN** the answer is `400` with `errors.role`
- **THEN** the message is shown on the role field
