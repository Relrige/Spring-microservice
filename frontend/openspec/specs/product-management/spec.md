# product-management Specification

## Purpose

The catalog manager's console for products: a staff list of every product, active or not, the forms to create and edit a product, and activation and deactivation, with Catalog's and Inventory's answers shown in plain words.

## Requirements

### Requirement: Staff product list
`/manage/products` SHALL list products from `GET /catalog/products` sent with the manager's session token, so that inactive products are listed together with active ones. Each row SHALL show a placeholder image, the title, the category name, the price, a status chip ("Active" / "Inactive") and a stock chip ("In stock" / "Out of stock"), with "Edit" and "Activate" or "Deactivate" on the right.

#### Scenario: Inactive products are listed
- **WHEN** a catalog manager opens `/manage/products`
- **THEN** the request carries the session token, and the table lists active and inactive products, ordered by title, each with its status chip

#### Scenario: Row actions follow the status
- **WHEN** a listed product is inactive
- **THEN** its row offers "Edit" and "Activate", and an active product's row offers "Edit" and "Deactivate"

#### Scenario: Unknown category in a row
- **WHEN** a product's category is not in the categories list
- **THEN** the row shows no category name and the neutral placeholder, and the rest of the row is shown

### Requirement: Product list toolbar and paging
The product list SHALL offer, in a toolbar above the table, a search (title and description), a category filter, a status filter ("All", "Active", "Inactive") and a sort order (title or price, each ascending or descending). It SHALL show 20 products per page with paging controls. The search, filters, sort and page SHALL be kept in the URL, so reload and the browser's back button restore them.

#### Scenario: Only inactive products
- **WHEN** the manager chooses the status "Inactive"
- **THEN** the request carries `status=NOT_ACTIVE`, only inactive products are listed, and the URL holds the choice

#### Scenario: List state restored from the URL
- **WHEN** the manager reloads `/manage/products?search=pixel&status=ACTIVE&page=1`
- **THEN** the search box shows "pixel", the status filter shows "Active" and the second page of matching products is listed

#### Scenario: Nothing matches
- **WHEN** no product matches the search and filters
- **THEN** the page shows "No products match your filters" and a control that clears them

### Requirement: Create a product
`/manage/products/new`, reached by a "New product" button on the list, SHALL create a product with `POST /catalog/products` from a title, a description, a category chosen from the category list and a price. After `201` the manager SHALL be taken to the new product's edit page with the notice "Product created. It stays inactive until you activate it."

#### Scenario: Product created
- **WHEN** the manager fills in valid values and selects "Create product"
- **THEN** the product is created, the edit page of the new product opens with the notice, and the product shows the status "Inactive"

#### Scenario: Category removed meanwhile
- **WHEN** the create request is answered `422` because the category does not exist
- **THEN** the category field shows "This category no longer exists. Choose another one.", the category list is read again and the other values are kept

### Requirement: Product field rules
The create and edit forms SHALL check Catalog's field rules before sending: title and description required and not blank, title at most 200 and description at most 5000 characters, a category chosen, and a price greater than 0 with at most two decimals. A value that breaks a rule SHALL be shown at its field and no request SHALL be sent. Field errors from a `400` answer SHALL be shown as described in the `api-access` capability.

#### Scenario: Blank description
- **WHEN** the manager submits the form with a description of only spaces
- **THEN** the description field shows that it is required and no request is sent

#### Scenario: Price of zero
- **WHEN** the manager enters the price `0`
- **THEN** the price field shows that the price must be greater than 0 and no request is sent

### Requirement: Edit form loads the full product
`/manage/products/{productId}` SHALL load the product from `GET /catalog/products/{productId}` sent with the manager's session token, so that inactive products load with their description, and fill the form with its title, description, category and price. It SHALL also show the product's status and stock status and its short id. A `404` SHALL show "This product does not exist" with a link back to the product list.

#### Scenario: Inactive product opens for editing
- **WHEN** the manager opens the edit page of an inactive product
- **THEN** the form shows its title, full description, category and price, and the status "Inactive"

#### Scenario: Unknown product
- **WHEN** the manager opens the edit page of an id that does not exist
- **THEN** the page shows "This product does not exist" and a link to the product list

### Requirement: Save product changes
Saving the edit form SHALL send `PUT /catalog/products/{productId}` with the four values. After `200` the form SHALL show the values from the answer and the notice "Changes saved". A `422` answer SHALL be handled as in *Create a product*.

#### Scenario: New price saved
- **WHEN** the manager changes the price and selects "Save changes"
- **THEN** the product is updated, "Changes saved" is shown, and the form shows the price from the answer

### Requirement: Overlapping save
When a save or a status change is answered `409`, the page SHALL show "This product was changed by someone else at the same time. Reload it and try again." with a "Reload product" button. The button SHALL read the product again and fill the form from the server, discarding unsaved edits. The request SHALL NOT be resent automatically.

#### Scenario: Two saves overlap
- **WHEN** a save is answered `409` with the problem type `conflict`
- **THEN** the message and "Reload product" are shown, the entered values stay in the form, and no request is resent

#### Scenario: Reload after a conflict
- **WHEN** the manager selects "Reload product"
- **THEN** the product is read again and the form shows the server's values

### Requirement: Activate a product
"Activate" on the list or the edit page SHALL send `PATCH /catalog/products/{productId}/status` with `ACTIVE`. After `200` the product SHALL be shown as "Active" with the notice "Product activated". While the request runs, the control SHALL be disabled.

#### Scenario: Product activated
- **WHEN** the manager activates an inactive product whose packaging is set
- **THEN** the request is answered `200`, the product shows "Active" and the notice "Product activated" appears

### Requirement: Activation rejected
A `422` with the problem type `activation-rejected` SHALL be explained by its `reason`: the two reasons Inventory documents get their own message, and any other reason is shown as "This product cannot be activated:" followed by the `detail`, or by the `reason` when there is no `detail`. The status SHALL stay as it was.

#### Scenario: Packaging missing
- **WHEN** activation is answered `422` `activation-rejected` with `reason` `packaging_data_missing`
- **THEN** "This product cannot be activated yet: its packaging size and weight are not set. An inventory worker sets them in the warehouse." is shown and the product stays "Inactive"

#### Scenario: No stock record yet
- **WHEN** activation is answered `422` `activation-rejected` with `reason` `stock_item_not_found`
- **THEN** "This product cannot be activated yet: the warehouse has no stock record for it. Try again in a moment." is shown and the product stays "Inactive"

#### Scenario: Unknown reason
- **WHEN** activation is answered `422` `activation-rejected` with `reason` `warehouse_closed` and no `detail`
- **THEN** "This product cannot be activated: warehouse_closed" is shown

### Requirement: Activation while Inventory is unavailable
A `503` answer to activation SHALL be shown as the "temporarily unavailable" message of the `api-access` capability, mentioning when to try again if `Retry-After` is present. The activation SHALL NOT be resent until the manager selects "Activate" again.

#### Scenario: Inventory down
- **WHEN** activation is answered `503` with `Retry-After: 5`
- **THEN** the message says the service is temporarily unavailable and to try again in 5 seconds, the product stays "Inactive", and no second request is sent

### Requirement: Deactivate a product
"Deactivate" SHALL first ask for confirmation ("Deactivate this product? Customers will no longer see it in the shop."). On confirmation it SHALL send `PATCH /catalog/products/{productId}/status` with `NOT_ACTIVE`; after `200` the product SHALL be shown as "Inactive" with the notice "Product deactivated". Cancelling the confirmation SHALL send nothing.

#### Scenario: Product deactivated
- **WHEN** the manager confirms the deactivation of an active product
- **THEN** the request is answered `200` and the product shows "Inactive"

#### Scenario: Deactivation cancelled
- **WHEN** the manager cancels the confirmation
- **THEN** no request is sent and the product stays "Active"

### Requirement: Manager changes reach the storefront
After any successful product or category change, the storefront pages opened afterwards in the same browser tab SHALL show the changed data without a reload. Product data read with the manager's token SHALL NOT be shown on storefront pages.

#### Scenario: Deactivated product leaves the storefront
- **WHEN** the manager deactivates a product and then opens the storefront product list
- **THEN** the product is not listed

#### Scenario: Staff list does not leak into the storefront
- **WHEN** the manager has loaded the staff list with inactive products and then opens the storefront
- **THEN** the storefront request carries no `Authorization` header and lists active products only

### Requirement: Manager pages load and fail like the rest of the app
While a manager list or the edit form is loading, a loading placeholder SHALL be shown in place of the content. Loading failures SHALL be shown as described in the `api-access` capability, with a way to try again. On narrow screens the table SHALL scroll inside its own area, and the page itself SHALL NOT scroll horizontally.

#### Scenario: Catalog unavailable
- **WHEN** loading the staff product list is answered `502`
- **THEN** the page shows the "temporarily unavailable" message and a "Try again" button

#### Scenario: Phone width
- **WHEN** the product list is opened at 375 px width
- **THEN** the page has no horizontal scroll and the table can be scrolled sideways within its area
