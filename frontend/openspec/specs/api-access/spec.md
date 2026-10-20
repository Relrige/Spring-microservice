# api-access Specification

## Purpose

Defines how the web frontend reaches the VoltStore backend through the API Gateway and how backend responses and failures are turned into consistent behaviour for the user.

## Requirements

### Requirement: Same-origin API prefix
The frontend SHALL send every backend request to a path under `/api` on its own origin. The part after `/api` SHALL be exactly the gateway route path (for example `/api/catalog/products` → gateway `/catalog/products`). The frontend SHALL NOT call services or the gateway on another origin.

#### Scenario: Catalog request goes through the prefix
- **WHEN** the storefront loads the product list
- **THEN** the browser requests `/api/catalog/products` on the page's own origin

#### Scenario: No cross-origin calls
- **WHEN** any page of the application is used
- **THEN** no request is sent to an origin other than the page's own origin

### Requirement: Session token on requests
When a session exists, the frontend SHALL send its token as `Authorization: Bearer <token>` on requests to routes that need or accept an identity, except where a capability states otherwise. It SHALL NOT send identity headers such as `X-User-Id`, `X-User-Role` or `X-User-Email`.

#### Scenario: Logged-in request carries the token
- **WHEN** a logged-in user triggers a request to a route that requires authentication
- **THEN** the request carries the `Authorization: Bearer` header with the session token

#### Scenario: Anonymous request has no token
- **WHEN** no session exists
- **THEN** requests carry no `Authorization` header

### Requirement: Problem details are read
The frontend SHALL read error responses in the problem-details format (`type`, `title`, `status`, `detail`, plus extension properties such as `errors`, `violation`, `reason`, `ineligibleItems`) and make them available to the page that made the request. A non-JSON or empty error body SHALL be handled using the HTTP status alone.

#### Scenario: Detail shown for a conflict
- **WHEN** a request is answered `409` with a problem detail whose `detail` is "Category name already in use"
- **THEN** the page that made the request can show that text to the user

#### Scenario: Body that is not JSON
- **WHEN** an error response has an empty or non-JSON body
- **THEN** the user sees a generic message for that status and the application keeps working

### Requirement: Field validation errors on forms
For a `400` response with an `errors` map, the frontend SHALL show each message next to the form field with the matching name. Messages for fields that the form does not show SHALL be shown in a general error area of that form.

#### Scenario: Server rejects a field
- **WHEN** a form is submitted and the answer is `400` with `errors: { "email": ["must be a well-formed email address"] }`
- **THEN** the message is shown at the email field and the other entered values are kept

#### Scenario: Error for a field not on the form
- **WHEN** the `errors` map names a field the form does not show
- **THEN** the message appears in the form's general error area

### Requirement: Expired or rejected session
When a request that carried the session token is answered `401`, the frontend SHALL end the session and send the user to the login page, keeping the page they were on as the place to return to after login. A `401` from the login request itself SHALL NOT be treated this way.

#### Scenario: Token expired during use
- **WHEN** a logged-in user's token has expired and a request with it is answered `401`
- **THEN** the session is cleared, the user is taken to the login page with a "Your session has expired" notice, and after logging in again returns to the page they were on

#### Scenario: Wrong password is not an expiry
- **WHEN** the login request is answered `401`
- **THEN** the login form shows "Invalid email or password" and no redirect happens

### Requirement: Forbidden responses
A `403` response SHALL be shown as "You do not have access to this action" without ending the session.

#### Scenario: Role not allowed
- **WHEN** a request is answered `403`
- **THEN** the user stays logged in and sees the access message

### Requirement: Unavailable backend
For `502`, `503`, `504` answers and for network failures (no answer), the frontend SHALL tell the user that the service is temporarily unavailable and offer a way to try again. It SHALL NOT automatically resend requests that change data. When a `503` carries `Retry-After`, the message SHALL mention when to try again.

#### Scenario: Gateway cannot reach a service
- **WHEN** loading the product list is answered `502`
- **THEN** the page shows a "temporarily unavailable" message with a "Try again" button that reloads the list

#### Scenario: Network down
- **WHEN** a request gets no response at all
- **THEN** the user sees "Cannot reach VoltStore. Check your connection and try again."

#### Scenario: Data-changing request is not resent
- **WHEN** a request that changes data fails with `503`
- **THEN** the frontend does not send it again unless the user acts

### Requirement: Unexpected errors
A `500` answer or an error the frontend cannot classify SHALL show a generic "Something went wrong" message. Technical details (stack traces, raw response bodies) SHALL NOT be shown to the user.

#### Scenario: Internal error
- **WHEN** a request is answered `500`
- **THEN** the user sees "Something went wrong. Please try again." and no technical details
