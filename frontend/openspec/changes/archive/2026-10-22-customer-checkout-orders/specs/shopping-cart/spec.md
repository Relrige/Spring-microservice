# Spec Delta

## MODIFIED Requirements

### Requirement: Checkout entry
The summary SHALL have a "Proceed to checkout" control. For a visitor it SHALL lead to the login page, returning to `/cart` afterwards. For a customer it SHALL run the soft check of the checkout (spec `checkout`) and open the checkout when it passes. It SHALL be disabled when the cart has no available line, and for a customer also while the cart shows an unavailable line or a line whose product details could not be loaded, with the note to remove unavailable products first.

#### Scenario: Visitor proceeds to checkout
- **WHEN** a visitor with a non-empty guest cart presses "Proceed to checkout"
- **THEN** the login page opens and, after logging in as a customer, the user returns to `/cart`

#### Scenario: Customer proceeds to checkout
- **WHEN** a customer whose cart lines are all available presses "Proceed to checkout"
- **THEN** the soft check is sent and, when it passes, the checkout opens

#### Scenario: Unavailable line blocks checkout
- **WHEN** a customer's cart shows a line marked "Unavailable"
- **THEN** "Proceed to checkout" is disabled and the summary says to remove unavailable products to check out

## ADDED Requirements

### Requirement: Cart after payment
After an approved payment the frontend SHALL read the customer's cart again. Customer Service removes the ordered products from the cart on its own, shortly after the payment; until then the ordered lines may still be shown, and products added after the order was placed stay in the cart.

#### Scenario: Ordered products leave the cart
- **WHEN** a customer pays for an order of all their cart lines
- **THEN** the cart is read again, and once Customer Service has removed the lines the header badge is hidden
