# warehouse-stock Specification

## Purpose

The inventory worker's stock page at Inventory Service: the stock rows with the product data Inventory copies from Catalog, finding and ordering them, adjusting stock, and setting the packaging values that activation and TTN generation need.

## Requirements

### Requirement: Stock page
`/warehouse` SHALL list the stock items from `GET /inventory/stock`, 20 per page, each row with the product title, the short product id, the Catalog status chip (Active / Inactive), available, reserved and total quantities, and whether packaging is set ("Missing" when not). The page SHALL show the number of stock items.

#### Scenario: Stock rows shown
- **WHEN** a worker opens `/warehouse`
- **THEN** each stock item shows its title, `#` and the first 8 characters of its product id, its Catalog status, its available, reserved and total quantities and its packaging state

#### Scenario: Title not copied yet
- **WHEN** a stock row has no `title` and no `productStatus`
- **THEN** the row shows the product id with "Title not available yet" and no status chip, and no Catalog request is sent

### Requirement: Finding and ordering stock
The stock page SHALL offer a search by title, a packaging filter (All, Packaging set, Packaging missing), a Catalog status filter (All, Active, Inactive) and a sort (Title A–Z, Available low to high, Reserved high to low), sent as `search`, `packagingConfigured`, `productStatus` and `sortBy`. The choices SHALL be kept in the URL, and changing one SHALL go back to the first page.

#### Scenario: Packaging missing
- **WHEN** the worker chooses "Packaging missing"
- **THEN** `GET /inventory/stock` is sent with `packagingConfigured=false` and only those rows are shown

#### Scenario: Search by title
- **WHEN** the worker searches for "sony"
- **THEN** the list shows the stock items whose title contains "Sony", and the URL keeps the search

### Requirement: Adjust stock
Each row SHALL offer "Adjust": a dialog to restock or write off a whole quantity of at least 1, sent as `POST /inventory/stock/{productId}/adjust` with a positive or negative `delta`. On success the row SHALL show the new available quantity. A write-off below zero (`422`) SHALL be shown as "Not enough available stock to write off"; a `503` SHALL show the unavailable-service message. Nothing is resent automatically.

#### Scenario: Restock
- **WHEN** the worker restocks 5 on a row with 3 available
- **THEN** `delta` 5 is sent and the row shows 8 available

#### Scenario: Write-off below zero
- **WHEN** the worker writes off 5 on a row with 3 available and the answer is `422`
- **THEN** the dialog stays open with "Not enough available stock to write off" and the row is unchanged

### Requirement: Set packaging
Each row SHALL offer "Set packaging": a dialog with gross weight (g), length, width and height (cm), all four required whole numbers above 0, replaced together by `PUT /inventory/stock/{productId}/packaging`. Because stored values cannot be read (BL-03), the form SHALL start empty and say that all four values are replaced. On `204` the row SHALL show packaging as set.

#### Scenario: Packaging saved
- **WHEN** the worker enters 350 g, 26, 21 and 10 cm and saves
- **THEN** the four values are sent and the row no longer shows "Missing"

#### Scenario: Missing value
- **WHEN** the worker leaves the height empty and saves
- **THEN** no request is sent and the height field says it is required

### Requirement: Stock page errors
A failed load SHALL show the error with "Try again". An empty result SHALL say that no stock items match the filters, with a way to clear them.

#### Scenario: No matching stock
- **WHEN** the filters match no stock item
- **THEN** "No stock items match these filters" and "Clear filters" are shown
