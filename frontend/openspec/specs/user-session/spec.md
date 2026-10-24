# user-session Specification

## Purpose

Covers how people register as customers, log in with any VoltStore role, stay logged in across page reloads, and log out, and which identity the frontend takes from the session token.

## Requirements

### Requirement: Customer registration
The frontend SHALL offer a registration form with email, password and password confirmation that sends `POST /user/register { email, password }`. Before sending, it SHALL check that the email looks valid and is at most 256 characters, that the password has 5–64 characters, and that both passwords match.

#### Scenario: Successful registration logs the user in
- **WHEN** a visitor submits a valid, unused email and matching passwords
- **THEN** the account is created, the frontend logs in with the same credentials, and the user lands on the storefront as a logged-in customer

#### Scenario: Email already used
- **WHEN** registration is answered `409`
- **THEN** the form shows "An account with this email already exists" at the email field and offers a link to the login page

#### Scenario: Client-side checks
- **WHEN** the password has fewer than 5 characters or the confirmation does not match
- **THEN** the form shows the problem at that field and no request is sent

### Requirement: Login
The frontend SHALL offer a login form with email and password that sends `POST /auth/login` and, on `200 { token }`, starts a session with that token. The same form SHALL serve every role.

#### Scenario: Successful login
- **WHEN** a user submits correct credentials
- **THEN** a session starts and the user is taken to the return page, if one was kept and their role may open it, otherwise to their role's landing area

#### Scenario: Invalid credentials
- **WHEN** login is answered `401`
- **THEN** the form shows "Invalid email or password", keeps the email, and clears the password

#### Scenario: Already logged in
- **WHEN** a logged-in user opens the login or registration page
- **THEN** they are sent to their role's landing area

### Requirement: Identity from the token
The frontend SHALL read the user id (`sub`), role (`role`), email (`email`) and expiry (`exp`) from the token's payload, without verifying the signature (the gateway verifies it). A token whose payload cannot be read, or whose role is not one of `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN`, SHALL be rejected and no session started.

#### Scenario: Header shows who is logged in
- **WHEN** a session exists
- **THEN** the header shows the user's email and role and a logout control

#### Scenario: Token without email claim
- **WHEN** the token has no `email` claim
- **THEN** the session still starts and the header shows the role only

#### Scenario: Unreadable token
- **WHEN** login returns a token whose payload cannot be decoded
- **THEN** no session starts and the login form shows a generic error

### Requirement: Session survives reload
The session SHALL be kept in the browser's local storage so that a page reload or a new tab keeps the user logged in until the token expires or they log out. Logging out in one tab SHALL end the session in the other open tabs.

#### Scenario: Reload keeps the session
- **WHEN** a logged-in user reloads the page before the token expires
- **THEN** they are still logged in

#### Scenario: Logout in another tab
- **WHEN** the user logs out in one tab
- **THEN** other open tabs of the application show the logged-out state without a reload

### Requirement: Session expiry
The session SHALL end when the token's `exp` time has passed, even if no request was made. An already-expired token found on page load SHALL NOT start a session. A notice SHALL tell the user that the session expired.

#### Scenario: Expiry while idle
- **WHEN** a logged-in user leaves the page open past the token's expiry
- **THEN** the session ends; if the current page requires a login, the user is taken to the login page with a "Your session has expired" notice

#### Scenario: Expired token on load
- **WHEN** the application starts with an expired token in storage
- **THEN** the token is discarded and the user is anonymous

### Requirement: Logout
The frontend SHALL let a logged-in user log out. Logout SHALL remove the token from the browser and drop all cached data loaded with it. The backend has no logout endpoint, so no request is sent.

#### Scenario: Logout
- **WHEN** the user chooses "Log out"
- **THEN** the session ends, data loaded for that user is no longer shown, and the user lands on the storefront as a visitor
