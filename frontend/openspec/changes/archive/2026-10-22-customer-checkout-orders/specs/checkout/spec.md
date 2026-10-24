# Spec Delta

## Purpose

The customer's way from the cart to a placed and paid order: the soft check, the delivery details, the order submission with stock reservation, and the card payment within the payment window, as Order Service defines them (EP-ORD-01…03).

## ADDED Requirements

### Requirement: Checkout needs a customer
`/checkout` and `/checkout/:orderId` SHALL require a logged-in customer. Every order request SHALL be sent with the session token; the customer is never identified by a value the frontend sends.

#### Scenario: Visitor opens the checkout
- **WHEN** a visitor who is not logged in opens `/checkout`
- **THEN** the login page opens and, after logging in as a customer, the checkout opens

### Requirement: Soft check before the checkout
When a customer proceeds from the cart, the frontend SHALL send `POST /orders/validate` with every cart line and open `/checkout` only on `204`. A `422` SHALL keep the customer on the cart page and name each listed product with its reason: no longer available (`NOT_ACTIVE`) or out of stock (`INSUFFICIENT_STOCK`).

#### Scenario: Cart passes the check
- **WHEN** a customer whose cart lines are all active and in stock presses "Proceed to checkout"
- **THEN** `POST /orders/validate` is sent with the cart lines and the delivery step opens

#### Scenario: Product became unavailable
- **WHEN** the soft check is answered `422` with one item `INSUFFICIENT_STOCK`
- **THEN** the cart page stays open and says that this product is out of stock and must be removed to check out

#### Scenario: Order Service unavailable at the check
- **WHEN** the soft check fails with `503`
- **THEN** the cart page shows the unavailable-service message and the checkout does not open

### Requirement: Checkout of an empty cart
Opening `/checkout` with an empty cart SHALL show that the cart is empty with a link to the catalog, and SHALL send no order request.

#### Scenario: Empty cart
- **WHEN** a customer with an empty cart opens `/checkout`
- **THEN** "Your cart is empty" and a link to the catalog are shown

### Requirement: Delivery details form
The delivery step SHALL show the cart lines with an estimated total and a form with recipient name, phone (international format), address and carrier. The carrier SHALL offer Nova Poshta only (BL-11). Every field is required.

#### Scenario: Missing recipient
- **WHEN** the customer submits the form with an empty recipient
- **THEN** no request is sent and the recipient field shows that it is required

#### Scenario: Carrier choice
- **WHEN** the delivery step opens
- **THEN** the carrier shows "Nova Poshta" and offers no other carrier

### Requirement: Save delivery details to the profile
The delivery step SHALL offer "Save these details to my profile", checked when the customer has no saved profile. When checked, a successful order submission SHALL also save the recipient (split into first and last name at the first space), phone and address with `PUT /customers/me/profile`. A failed profile save SHALL NOT stop the checkout.

#### Scenario: Details saved with the order
- **WHEN** a customer without a profile submits recipient "Olena Kovalenko" with the option checked and the order is placed
- **THEN** `PUT /customers/me/profile` is sent with first name "Olena" and last name "Kovalenko"

#### Scenario: Profile save fails
- **WHEN** the order is placed but the profile save fails with `503`
- **THEN** the payment step opens and no error blocks it

### Requirement: Order submission
"Place order" SHALL send `POST /orders` with the cart lines and the delivery details, and on `201` open the payment step of the new order. The button SHALL be disabled while the request runs. The order is placed from the current cart; prices come from the answer, never from the client.

#### Scenario: Order placed
- **WHEN** the customer submits valid delivery details for a cart of 2 lines
- **THEN** `POST /orders` is sent with both lines and the delivery details, and the payment step of the returned order opens

### Requirement: Idempotent submission
Every submission SHALL carry an `Idempotency-Key` (a UUID). A retry of the same attempt (same cart lines and delivery details) SHALL reuse the key; a change of the cart lines or of the delivery details SHALL start a new key. An answer marked as a replay SHALL be treated like any `201`.

#### Scenario: Retry after an unavailable service
- **WHEN** a submission fails with `503` and the customer presses "Place order" again without changes
- **THEN** the second `POST /orders` carries the same `Idempotency-Key` as the first

#### Scenario: Details changed before a retry
- **WHEN** a submission fails, the customer changes the address and submits again
- **THEN** the second `POST /orders` carries a new `Idempotency-Key`

#### Scenario: Replayed answer
- **WHEN** a repeated submission is answered `201` with the first order (header `Idempotent-Replayed`)
- **THEN** the payment step of that order opens and no second order is shown

### Requirement: Submission problems
A rejected submission SHALL keep the customer on the delivery step with the entered values and a message for the problem kind (by its `type`), and no order is shown. A submission still running elsewhere (`409` request in progress) SHALL be repeated once with the same key after the `Retry-After` delay.

#### Scenario: Product no longer available
- **WHEN** the submission is answered `422` ineligible items for one product
- **THEN** the message names the product as no longer available, with a link back to the cart

#### Scenario: Order total too large
- **WHEN** the submission is answered `422` order total too large with `maxTotal`
- **THEN** the message says the order exceeds the maximum total, with that amount

#### Scenario: Key used for other details
- **WHEN** the submission is answered `422` idempotency key mismatch
- **THEN** the message asks to place the order again, and the next submission carries a new key

#### Scenario: Service unavailable at submission
- **WHEN** the submission is answered `503`
- **THEN** the unavailable-service message is shown and nothing is sent again automatically

#### Scenario: Not enough stock
- **WHEN** the submission is answered `422` insufficient stock for a product requested 3 with 1 available
- **THEN** the message names the product with "requested 3, available 1" and the form keeps its values

#### Scenario: Submission still running
- **WHEN** the submission is answered `409` request in progress with `Retry-After: 1`
- **THEN** after about 1 second the same request is sent once more with the same key

### Requirement: Payment step
`/checkout/:orderId` SHALL show an order awaiting payment: its short id, the "pay by" time from `reservationExpiresAt` with the minutes left, the delivery details, the items with order-time prices and the total, and the card form. For an order in any other status it SHALL show the status with a link to the order page instead of the form.

#### Scenario: Order awaiting payment
- **WHEN** a customer opens the payment step of an order placed at 14:05 with a 15-minute window
- **THEN** the page says to pay by 14:20 and shows the card form and "Pay UAH" with the order total

#### Scenario: Order already paid
- **WHEN** the customer opens the payment step of a paid order
- **THEN** no card form is shown, and the page says the order is paid with a link to its order page

#### Scenario: Unknown order
- **WHEN** the order is answered `404`
- **THEN** the page says the order was not found with a link to the order history

### Requirement: Card form
The card form SHALL take the card number (13–19 digits, spaces allowed while typing), the expiry as MM/YY (not before the current month) and the CVV (3–4 digits), and SHALL check them before sending `POST /orders/{orderId}/pay`. Card data SHALL NOT be stored in the browser, in the URL or in any cache, and SHALL be cleared from the form after an approved payment. The page SHALL list the demo provider's test cards.

#### Scenario: Expired card
- **WHEN** the customer enters an expiry in the past and presses "Pay"
- **THEN** no request is sent and the expiry field says the card has expired

#### Scenario: Test cards shown
- **WHEN** the payment step is shown
- **THEN** it lists the test cards ending 4242 (approved), 0002 (declined, insufficient funds), 5000 (answer lost, pay again with the same card) and any other (declined)

### Requirement: Approved payment
The "Pay" button SHALL be disabled while the payment runs. An approved payment (`200`) SHALL open the order page with "Payment received" and read the cart again.

#### Scenario: Approved payment
- **WHEN** the customer pays with a card ending 4242 and the answer is `200`
- **THEN** the order page opens with "Payment received" and the cart is read again from `GET /customers/cart`

### Requirement: Failed payment
A failed payment SHALL keep the order and show a message for the problem kind (by status and `type`): a declined card with its reason, after which another card may be tried; an elapsed payment window, which replaces the form with a link to the order; a payment still in progress; or an unavailable service, after which trying again with the same card is safe. Nothing is resent automatically.

#### Scenario: Payment in progress
- **WHEN** the payment is answered `409` with type `conflict` and no order status
- **THEN** the page says the previous payment is still being processed and to try again with the same card

#### Scenario: Card declined
- **WHEN** the customer pays with a card ending 0002 and the answer is `402` with reason `insufficient_funds`
- **THEN** "This card was declined: insufficient funds." is shown with the pay-by time, and the form stays for another card

#### Scenario: Payment window elapsed
- **WHEN** the payment is answered `409` with type `payment-window-expired`
- **THEN** the page says the payment window has passed and offers a link to the order instead of the form

#### Scenario: Payment service unavailable
- **WHEN** the payment is answered `503`
- **THEN** the page says the payment could not be confirmed and that trying again with the same card will not charge twice

### Requirement: Payment window countdown
While an order awaits payment, the payment step SHALL show the minutes left and SHALL replace the form with an expired notice when `reservationExpiresAt` has passed, without waiting for a server answer.

#### Scenario: Window passes while the page is open
- **WHEN** the pay-by time passes while the payment step is open
- **THEN** the card form is replaced by "The payment window has passed" with a link to the order
