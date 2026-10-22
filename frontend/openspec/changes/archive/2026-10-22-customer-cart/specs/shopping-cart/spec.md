# Spec Delta

## Purpose

The cart in which visitors and customers collect products before checkout: a guest cart kept in the browser, the customer's cart kept by Customer Service, their shared limits, the cart page with live product data, and how a guest cart is carried into the account at login.

## ADDED Requirements

### Requirement: Cart owner by role
A visitor who is not logged in SHALL have a guest cart kept only in the browser. A logged-in customer SHALL have the server cart of Customer Service (`/customers/cart*`) and no guest cart. Catalog managers, inventory workers and admins SHALL have no cart; the guest cart in their browser is left untouched while they are logged in.

#### Scenario: Visitor adds a product
- **WHEN** a visitor adds a product to the cart
- **THEN** no request is sent to Customer Service and the product appears in the cart

#### Scenario: Customer adds a product
- **WHEN** a logged-in customer adds a product to the cart
- **THEN** the frontend sends `POST /customers/cart/items` and shows the cart from the answer

#### Scenario: Staff login keeps the guest cart
- **WHEN** a visitor with a guest cart logs in as an inventory worker and later logs out
- **THEN** no cart request is sent while the worker is logged in, and after logout the guest cart is shown unchanged

### Requirement: Guest cart kept in the browser
The guest cart SHALL hold only product ids and quantities, SHALL survive a page reload and SHALL be the same in every tab of the browser. Stored data that cannot be read SHALL be discarded and treated as an empty cart.

#### Scenario: Reload keeps the guest cart
- **WHEN** a visitor with two products in the guest cart reloads the page
- **THEN** the cart still holds the same two products and quantities

#### Scenario: Change in another tab
- **WHEN** a visitor adds a product in one tab while the cart page is open in another
- **THEN** the other tab shows the added product without a reload

#### Scenario: Unreadable stored cart
- **WHEN** the stored guest cart is not valid data
- **THEN** the cart is shown as empty and no error is shown

### Requirement: Cart limits
A cart SHALL hold at most 10 different products, each with a quantity from 1 to 10, checked on the result of every change (for example, the quantity after adding to an existing line). A change that would break a limit SHALL be rejected as a whole, leaving the cart unchanged, with a message naming the limit. The guest cart SHALL apply the same limits and messages as the `422` answers of Customer Service (`TOO_MANY_PRODUCTS`, `QUANTITY_LIMIT_EXCEEDED`).

#### Scenario: Eleventh product
- **WHEN** a cart holds 10 different products and the user adds another product
- **THEN** the cart is unchanged and the message "Your cart can hold up to 10 different products." is shown

#### Scenario: Quantity above ten
- **WHEN** a cart line has quantity 8 and the user adds 3 more of that product
- **THEN** the cart is unchanged and the message "You can have up to 10 of each product in your cart." is shown

#### Scenario: Server rejects a change
- **WHEN** Customer Service answers a cart change with `422` and `violation` `TOO_MANY_PRODUCTS` or `QUANTITY_LIMIT_EXCEEDED`
- **THEN** the matching message is shown and the cart is reloaded from `GET /customers/cart`

### Requirement: Server cart follows the server
For a customer, every cart change SHALL be sent to Customer Service, and the cart shown SHALL be the cart in the server's answer. A change that fails SHALL leave the shown cart as the server last reported it and SHALL show a message; changes are not resent automatically. While a change of a line is in progress, that line's controls SHALL be disabled.

#### Scenario: Line removed on another device
- **WHEN** a customer changes the quantity of a line that was removed on another device and the server answers `404`
- **THEN** the message "This product is no longer in your cart." is shown and the cart is reloaded

#### Scenario: Customer Service unavailable
- **WHEN** a cart change fails with `503` or a network error
- **THEN** the cart is left as it was, the unavailable-service message is shown and the request is not repeated

#### Scenario: Cart opened on a second device
- **WHEN** a customer opens the cart page
- **THEN** the cart is read again with `GET /customers/cart`, so changes made on other devices are shown

### Requirement: Cart page lines
`/cart` SHALL list the cart lines, each with live product data from `GET /catalog/products/{productId}` (sent without the session token): the image placeholder, the title linking to the product page, the unit price, a quantity stepper and the line total. The cart itself stores only product ids and quantities.

#### Scenario: Cart with two products
- **WHEN** a cart holds 1 of a product priced UAH 12,999.00 and 2 of a product priced UAH 4,899.00
- **THEN** both lines show title, unit price and quantity, with line totals UAH 12,999.00 and UAH 9,798.00

#### Scenario: Product details cannot be loaded
- **WHEN** the product request of a line fails with `503` or a network error
- **THEN** that line shows "Product details could not be loaded" with a "Try again" control and can still be removed, and the other lines are shown normally

### Requirement: Unavailable cart lines
A line whose product answers `404` (inactive or deleted) or whose `stockStatus` is `OUT_OF_STOCK` SHALL stay in the cart, marked "Unavailable", with its quantity stepper disabled, left out of the item count and the estimated subtotal, and removable (US-CUST-04).

#### Scenario: Product deactivated after it was added
- **WHEN** a cart line's product answers `404`
- **THEN** the line shows "Product no longer available" with an "Unavailable" label, "Not counted in the subtotal" and a "Remove" control

#### Scenario: Product sold out after it was added
- **WHEN** a cart line's product has `stockStatus` `OUT_OF_STOCK`
- **THEN** the line shows its title and the "Unavailable" label, and its price is not counted in the subtotal

### Requirement: Change quantity and remove
On the cart page the user SHALL be able to set a line's quantity with a stepper from 1 to 10 and remove a line. For a customer, a quantity change SHALL send `PATCH /customers/cart/items/{productId}` with the new quantity, and removal SHALL send `DELETE /customers/cart/items/{productId}`.

#### Scenario: Increase a quantity
- **WHEN** a customer presses "+" on a line with quantity 2
- **THEN** `PATCH /customers/cart/items/{productId}` is sent with quantity 3 and the line shows 3

#### Scenario: Stepper bounds
- **WHEN** a line has quantity 1
- **THEN** "−" is disabled, and when a line has quantity 10, "+" is disabled

#### Scenario: Remove a line
- **WHEN** the user presses "Remove" on a line
- **THEN** the line disappears from the cart and from the item count

### Requirement: Clear the cart
The cart page SHALL offer to remove all lines after a confirmation. For a customer, clearing SHALL send `DELETE /customers/cart`.

#### Scenario: Clear after confirming
- **WHEN** the user chooses "Clear cart" and confirms
- **THEN** the cart is empty and the empty-cart state is shown

#### Scenario: Clear cancelled
- **WHEN** the user chooses "Clear cart" and cancels
- **THEN** the cart is unchanged and no request is sent

### Requirement: Cart summary
The cart page SHALL show a summary with the item count (sum of quantities of available lines) and the estimated subtotal (sum of available line totals), with the note that prices and availability are checked again at checkout. While a line's product data is still loading or failed to load, the subtotal SHALL say that it is incomplete.

#### Scenario: Summary with an unavailable line
- **WHEN** the cart holds the two products of *Cart with two products* and one unavailable line
- **THEN** the summary shows "Items 3" and "Estimated subtotal UAH 22,797.00"

### Requirement: Empty cart
A cart without lines SHALL show "Your cart is empty" and a link to the catalog instead of the lines and summary.

#### Scenario: New visitor opens the cart
- **WHEN** a visitor with no guest cart opens `/cart`
- **THEN** "Your cart is empty" and a link to the catalog are shown

### Requirement: Checkout entry
The summary SHALL have a "Proceed to checkout" control. For a visitor it SHALL lead to the login page, returning to `/cart` afterwards. For a customer it SHALL be disabled with the note that checkout is not available yet, until checkout exists (iteration 3). It SHALL be disabled when the cart has no available line.

#### Scenario: Visitor proceeds to checkout
- **WHEN** a visitor with a non-empty guest cart presses "Proceed to checkout"
- **THEN** the login page opens and, after logging in as a customer, the user returns to `/cart`

### Requirement: Cart count in the header
The header SHALL show a cart control linking to `/cart` with a badge showing the total quantity of all lines, for visitors and customers. The badge SHALL be hidden when the cart is empty. For a customer the count SHALL come from the server cart.

#### Scenario: Count after adding
- **WHEN** a visitor with an empty cart adds 2 of a product
- **THEN** the header cart badge shows 2

### Requirement: Guest cart on login
When a customer is logged in and the guest cart is not empty (after login or registration in this tab, or when the application loads), the frontend SHALL read the account cart. If the account cart is empty, it SHALL replace it with the guest cart (`PUT /customers/cart`) without asking. If both carts have lines, it SHALL ask the customer (next requirement). An empty guest cart SHALL leave the account cart untouched.

#### Scenario: Empty account cart
- **WHEN** a visitor with 2 products in the guest cart registers
- **THEN** `PUT /customers/cart` is sent with those 2 products, no question is asked, and the cart shows them

#### Scenario: Empty guest cart
- **WHEN** a customer with items in the account cart logs in from a browser with an empty guest cart
- **THEN** no question is asked and the account cart is shown unchanged

### Requirement: Replace, keep or merge choice
When both carts have lines, a dialog SHALL list both carts and offer "Replace with this browser's cart" (`PUT /customers/cart` with the guest lines), "Keep account cart" (`GET /customers/cart`) and "Merge both" (`POST /customers/cart/merge` with the guest lines; quantities are summed). The dialog SHALL stay open until a choice succeeds.

#### Scenario: Merge
- **WHEN** the account cart holds 1 of product A, the guest cart holds 2 of product A and 1 of product B, and the customer chooses "Merge both"
- **THEN** `POST /customers/cart/merge` is sent and the cart shows 3 of A and 1 of B

#### Scenario: Keep account cart
- **WHEN** the customer chooses "Keep account cart"
- **THEN** no cart change is sent and the account cart is shown

#### Scenario: Merge breaks a limit
- **WHEN** the merge is answered `422` `QUANTITY_LIMIT_EXCEEDED`
- **THEN** the dialog stays open with the limit message, and the other choices can still be made

### Requirement: Guest cart discarded after login
After the guest cart was applied or the customer chose to keep the account cart, the guest cart SHALL be emptied in the browser. If reading the account cart or a choice fails, the guest cart SHALL be kept and the customer SHALL be able to try again. After logout the visitor starts with an empty guest cart.

#### Scenario: Logout after merge
- **WHEN** a customer merged the guest cart and then logs out
- **THEN** the visitor's cart is empty

#### Scenario: Customer Service down at login
- **WHEN** reading the account cart after login fails with `503`
- **THEN** the guest cart is kept, the unavailable-service message is shown with a "Try again" control, and trying again resumes the login rule
