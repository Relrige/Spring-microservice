# Spec Delta

## Purpose

The catalog manager's console for categories: a searchable list, and creating, renaming and deleting categories with Catalog's uniqueness and "no products left" rules shown in plain words.

## ADDED Requirements

### Requirement: Category list
`/manage/categories` SHALL list categories from `GET /catalog/categories`, ordered by name, 20 per page with paging controls, with a search box that filters by part of the name. Each row SHALL show the name, the short id, and "Rename" and "Delete" on the right. The search and page SHALL be kept in the URL.

#### Scenario: Categories listed
- **WHEN** a catalog manager opens `/manage/categories`
- **THEN** the categories are listed by name, each with "Rename" and "Delete"

#### Scenario: Search by name
- **WHEN** the manager searches for "pho"
- **THEN** the request carries `search=pho`, only matching categories are listed, and the URL holds the search

#### Scenario: No categories match
- **WHEN** no category matches the search
- **THEN** the page shows "No categories match your search" and a control that clears it

### Requirement: Category name rules
The create and rename forms SHALL require a name that is not blank and at most 100 characters, checked before sending. A name that breaks a rule SHALL be shown at the name field and no request SHALL be sent.

#### Scenario: Blank name
- **WHEN** the manager submits a category name of only spaces
- **THEN** the name field shows that it is required and no request is sent

### Requirement: Create a category
A "New category" button SHALL open a form that creates a category with `POST /catalog/categories`. After `201` the form SHALL close, the notice "Category created" SHALL appear and the list SHALL show the new category. A `409` SHALL be shown at the name field as "A category with this name already exists." with the entered name kept.

#### Scenario: Category created
- **WHEN** the manager creates the category "Tablets"
- **THEN** "Category created" appears and "Tablets" is in the list

#### Scenario: Name in use
- **WHEN** creating a category is answered `409`
- **THEN** the name field shows "A category with this name already exists." and the form stays open with the name

### Requirement: Rename a category
"Rename" SHALL open a form pre-filled with the current name that sends `PUT /catalog/categories/{categoryId}`. After `200` the list SHALL show the new name and the notice "Category renamed". A `409` SHALL be handled as in *Create a category*. A `404` SHALL close the form, show "This category no longer exists." and read the list again.

#### Scenario: Category renamed
- **WHEN** the manager renames "Audio" to "Audio and sound"
- **THEN** "Category renamed" appears and the list shows "Audio and sound"

#### Scenario: Category deleted meanwhile
- **WHEN** a rename is answered `404`
- **THEN** the form closes, "This category no longer exists." is shown and the list is read again

### Requirement: Delete a category
"Delete" SHALL first ask for confirmation naming the category. On confirmation it SHALL send `DELETE /catalog/categories/{categoryId}`; after `200` the notice "Category deleted" SHALL appear and the list SHALL be read again. A `409` SHALL be shown as "This category still has products. Move them to another category first." and the category SHALL stay listed. A `404` SHALL be treated as already deleted.

#### Scenario: Empty category deleted
- **WHEN** the manager confirms the deletion of a category without products
- **THEN** "Category deleted" appears and the category is no longer listed

#### Scenario: Category with products
- **WHEN** the deletion is answered `409`
- **THEN** the message about remaining products is shown and the category stays in the list

#### Scenario: Deletion cancelled
- **WHEN** the manager cancels the confirmation
- **THEN** no request is sent

### Requirement: Category changes reach the product pages
After a category is created, renamed or deleted, the category choices in the product forms and filters, and the category names in the manager's product list and on storefront pages, SHALL show the change without a reload.

#### Scenario: Renamed category in the product list
- **WHEN** the manager renames a category and then opens `/manage/products`
- **THEN** the products of that category show the new name

#### Scenario: New category offered in the product form
- **WHEN** the manager creates a category and then opens "New product"
- **THEN** the new category can be chosen
