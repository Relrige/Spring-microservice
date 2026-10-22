# catalog-browsing Specification

## Purpose

The storefront where visitors and customers find products: a filterable, sortable, paged product list and a product details page, both showing only what Catalog Service exposes to customers (active products with coarse stock status).

## Requirements

### Requirement: Storefront shows the customer view
The storefront SHALL show the catalog as customers see it (active products only), whoever is logged in. Storefront catalog requests SHALL be sent without the session token, so that a logged-in catalog manager does not see inactive products in the storefront.

#### Scenario: Manager browses the storefront
- **WHEN** a logged-in catalog manager opens the storefront product list
- **THEN** the request carries no `Authorization` header and only active products are listed

### Requirement: Product list
The storefront home `/` SHALL list products from `GET /catalog/products`, 20 per page. Each entry SHALL show the title, the price and the stock status ("In stock" / "Out of stock"), and link to the product's details page. Prices SHALL be shown in hryvnia with two decimals.

#### Scenario: List loads
- **WHEN** a visitor opens `/`
- **THEN** the first 20 active products are shown, ordered by title, each with title, price, stock status and a link to its details

#### Scenario: Out-of-stock product is listed
- **WHEN** a product's `stockStatus` is `OUT_OF_STOCK`
- **THEN** the product is listed with an "Out of stock" label

#### Scenario: Empty catalog
- **WHEN** no product matches the current filters
- **THEN** the page shows "No products match your filters" and a control to clear the filters

### Requirement: Search and filters
The product list SHALL offer a keyword search (sent as `search`), a category filter (sent as `categoryId`) and a price range (sent as `minPrice` and `maxPrice`). Category choices SHALL come from `GET /catalog/categories`, ordered by name. Changing a filter SHALL return to the first page.

#### Scenario: Search by keyword
- **WHEN** the user searches for "laptop"
- **THEN** the list shows the products returned for `search=laptop`, starting at the first page

#### Scenario: Filter by category
- **WHEN** the user picks the category "Phones"
- **THEN** the list shows only products returned for that `categoryId`

#### Scenario: Invalid price range
- **WHEN** the minimum price is greater than the maximum price, or either is negative
- **THEN** the form shows the problem at the price fields and the list is not requested with that range

#### Scenario: Categories unavailable
- **WHEN** loading categories fails
- **THEN** the product list still works, and the category filter shows that categories could not be loaded

### Requirement: Sorting
The product list SHALL offer the sort orders "Name A–Z", "Name Z–A", "Price low to high" and "Price high to low", sent as `sortBy` `title,asc`, `title,desc`, `basePrice,asc` and `basePrice,desc`. "Name A–Z" SHALL be the default.

#### Scenario: Sort by price
- **WHEN** the user picks "Price low to high"
- **THEN** the list is requested with `sortBy=basePrice,asc` and shown in that order

### Requirement: Paging
The product list SHALL show the current page, the total number of matching products, and controls to move to the previous and next page. Controls that lead outside the result SHALL be disabled.

#### Scenario: Next page
- **WHEN** there are 45 matching products and the user is on the first page
- **THEN** "Next" loads products 21–40, and on the third page "Next" is disabled

### Requirement: List state in the URL
Search, category, price range, sort order and page SHALL be kept in the URL query string, so that reloading, sharing the link, or using the browser's back and forward buttons shows the same list. Missing or invalid values in the URL SHALL fall back to the defaults.

#### Scenario: Shared link
- **WHEN** a user opens a link with `?search=tv&sort=basePrice,desc&page=2`
- **THEN** the list shows the second page of products matching "tv", most expensive first

#### Scenario: Back button
- **WHEN** the user changes the category and then presses the browser's back button
- **THEN** the previous category selection and its results are shown again

#### Scenario: Invalid URL value
- **WHEN** the URL has `page=-3` or an unknown sort value
- **THEN** the list uses the default for that value instead of showing an error

### Requirement: Product details page
`/products/{productId}` SHALL show the product from `GET /catalog/products/{productId}`: title, description, price, category name and stock status. The category name SHALL come from the categories list; if it cannot be found, the category is omitted.

#### Scenario: Active product
- **WHEN** a user opens the details page of an active product
- **THEN** the title, full description, price, category name and stock status are shown

#### Scenario: Missing or inactive product
- **WHEN** the product does not exist, is not active, or the id in the URL is not a valid UUID (all answered `404`)
- **THEN** the page shows "This product is not available" with a link back to the catalog

### Requirement: Product image placeholder
Catalog provides no product images, so every product in the list and on its details page SHALL show a placeholder image chosen by its category: products of the same category show the same placeholder, and different categories are visually distinguishable. A product whose category is unknown SHALL show a neutral default placeholder.

#### Scenario: Same category, same placeholder
- **WHEN** two listed products belong to the category "Phones"
- **THEN** both show the same placeholder image, different from the placeholder of a "Laptops" product

#### Scenario: Unknown category
- **WHEN** a product's category cannot be found in the categories list
- **THEN** the product shows the neutral default placeholder

### Requirement: Loading and failure states
While the product list or details page is loading, a loading indicator SHALL be shown in place of the content. If loading fails, the failure SHALL be shown as described in the `api-access` capability, with a way to try again.

#### Scenario: Catalog service down
- **WHEN** loading the product list is answered `502`
- **THEN** the page shows the "temporarily unavailable" message and a "Try again" button instead of the list

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
