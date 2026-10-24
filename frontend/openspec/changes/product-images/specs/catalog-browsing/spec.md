## MODIFIED Requirements

### Requirement: Product image placeholder
Every product in the list, on its details page, in the cart and in the manager's product list SHALL show its image from `imageUrl` when Catalog returns one, on the ground colour of its category. A product without an image, or whose image cannot be loaded, SHALL show a placeholder image chosen by its category: products of the same category show the same placeholder, and different categories are visually distinguishable. A product whose category is unknown SHALL show a neutral default placeholder. The image SHALL be decorative (the product's title is shown next to it).

#### Scenario: Product with an image
- **WHEN** a listed product has the `imageUrl` `/images/products/phone.svg`
- **THEN** its tile shows that image instead of the category placeholder

#### Scenario: Image fails to load
- **WHEN** a product's image cannot be loaded
- **THEN** the product shows its category placeholder instead

#### Scenario: Same category, same placeholder
- **WHEN** two listed products without an image belong to the category "Phones"
- **THEN** both show the same placeholder image, different from the placeholder of a "Laptops" product

#### Scenario: Unknown category
- **WHEN** a product without an image has a category that cannot be found in the categories list
- **THEN** the product shows the neutral default placeholder
