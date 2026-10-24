## MODIFIED Requirements

### Requirement: Set packaging
Each row SHALL offer "Set packaging": a dialog with gross weight (g), length, width and height (cm), all four required whole numbers above 0, replaced together by `PUT /inventory/stock/{productId}/packaging`. The dialog SHALL first read the stock item with `GET /inventory/stock/{productId}` and fill each field with its stored value, leaving a field empty when that value is not set. While the values load, the dialog SHALL show a loading state; a failed read SHALL show the error with "Try again". On `204` the row SHALL show packaging as set.

#### Scenario: Stored values shown
- **WHEN** the worker opens "Set packaging" for a product whose packaging is 350 g, 26, 21 and 10 cm
- **THEN** the four fields show 350, 26, 21 and 10

#### Scenario: Packaging not set yet
- **WHEN** the worker opens "Set packaging" for a product without packaging
- **THEN** the four fields are empty

#### Scenario: Packaging saved
- **WHEN** the worker enters 350 g, 26, 21 and 10 cm and saves
- **THEN** the four values are sent and the row no longer shows "Missing"

#### Scenario: Missing value
- **WHEN** the worker leaves the height empty and saves
- **THEN** no request is sent and the height field says it is required
