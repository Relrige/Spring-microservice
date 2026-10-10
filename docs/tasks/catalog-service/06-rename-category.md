---
status: DONE
service: catalog-service
---
# EP-CAT-07: Rename Category

## Context
A Catalog Manager renames an existing category. Products keep pointing at the same `categoryId`, so renaming never touches product rows.
*References:* [Catalog Spec — EP-CAT-07](../../design/services-specs/catalog-service-spec.md), [Catalog Manager Scenarios (SCN-CM06)](../../design/scenarios/catalog-manager-scenarios.md)

## Acceptance Criteria
- [x] `PUT /catalog/categories/{id}` accepts `{ name }` and returns `200 OK { categoryId, name }`.
- [x] Category `id` not found → `404`.
- [x] Blank `name` → `400` with field errors.
- [x] Another category already has that `name`, ignoring case (see [task 02](02-category-entity-and-persistence.md)) → `409`.
- [x] Renaming a category to its own current name, or only changing its casing (`laptops` → `Laptops`), succeeds and is not reported as a conflict.
- [x] A concurrent rename colliding on the unique constraint returns `409`, not `500`.
- [x] Tests: service unit tests and controller/integration tests for each edge case in the spec.

## Technical Notes / Constraints
- The "another category" check must exclude the category being renamed, otherwise a no-op rename always conflicts with itself.
- The order of checks follows the spec: existence (`404`) before body validation (`400`) is the spec's order for the product update; for categories the spec lists existence first as well. Keep the order the spec states and make a test that pins it.
- A non-UUID `{id}` → `400` (type mismatch, from [task 04](04-error-handling-and-validation.md)).
- Authorization is added in [task 12](12-trusted-header-security.md); until then the endpoint is open (local use only).
- **Check order (implemented):** the endpoint has no `@Valid`. The service loads the category (`404`), then validates the body through `RequestValidator` (`400`), then checks for duplicates (`409`). Pinned by `rename_unknownIdAndBlankName_returns404BecauseExistenceIsCheckedFirst`. Syntactically malformed JSON is still a `400` before the `404`, because the body must be parsed before the controller runs.
