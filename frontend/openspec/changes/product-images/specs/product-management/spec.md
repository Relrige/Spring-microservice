## ADDED Requirements

### Requirement: Product image field
The create and edit forms SHALL offer an optional "Image" field for the product's `imageUrl`, filled from the product on the edit page, with a preview of the image when the field holds a valid value. Before sending, the value SHALL be trimmed and checked against Catalog's rule: an `https://` URL or a path under `/images/` without `..` or spaces, at most 2048 characters; a value that breaks the rule SHALL be shown at the field and no request SHALL be sent. An empty field SHALL send no image. A field error for `imageUrl` in a `400` answer SHALL be shown at the field.

#### Scenario: Image set on create
- **WHEN** the manager enters `/images/products/laptop.svg` in the Image field and creates the product
- **THEN** the create request carries that `imageUrl`, and the edit page shows it in the field and in the preview

#### Scenario: Invalid image value
- **WHEN** the manager enters `http://example.com/a.png` in the Image field and submits
- **THEN** the Image field says to use an https link or a path under /images/, and no request is sent

#### Scenario: Image removed
- **WHEN** the manager clears the Image field of a product that has an image and saves
- **THEN** the update request carries no image and the product shows its category placeholder afterwards

## MODIFIED Requirements

### Requirement: Save product changes
Saving the edit form SHALL send `PUT /catalog/products/{productId}` with the form's values, including the image (or none). After `200` the form SHALL show the values from the answer and the notice "Changes saved". A `422` answer SHALL be handled as in *Create a product*.

#### Scenario: New price saved
- **WHEN** the manager changes the price and selects "Save changes"
- **THEN** the product is updated, "Changes saved" is shown, and the form shows the price from the answer

#### Scenario: Image kept on save
- **WHEN** the manager changes only the price of a product that has an image and saves
- **THEN** the update request carries the unchanged `imageUrl`, so the image stays
