# Spec Delta

## ADDED Requirements

### Requirement: Add to cart from the product page
The product details page SHALL offer a quantity selector (1–10, default 1) and an "Add to cart" button to visitors and customers. Adding SHALL put the chosen quantity into the cart (spec `shopping-cart`) and confirm with "Added to cart" and a link to the cart. For an out-of-stock product the button SHALL be disabled. Staff SHALL see no quantity selector and no button.

#### Scenario: Visitor adds two
- **WHEN** a visitor sets the quantity to 2 on an in-stock product and presses "Add to cart"
- **THEN** the cart holds 2 of that product and "Added to cart" is shown with a link to the cart

#### Scenario: Out-of-stock product
- **WHEN** a visitor opens the details page of a product with `stockStatus` `OUT_OF_STOCK`
- **THEN** the "Add to cart" button is disabled

#### Scenario: Manager sees no cart action
- **WHEN** a logged-in catalog manager opens a product's details page
- **THEN** no quantity selector and no "Add to cart" button are shown

#### Scenario: Add is rejected by a limit
- **WHEN** adding would break a cart limit (spec `shopping-cart`, *Cart limits*)
- **THEN** the limit message is shown on the product page and the cart is unchanged
