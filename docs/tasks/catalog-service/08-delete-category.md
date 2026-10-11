---
status: DONE
service: catalog-service
---
# EP-CAT-08: Delete Category

## Context
A Catalog Manager removes a category that is no longer used. Categories (unlike products) are hard-deleted, but only when no product references them.
*References:* [Catalog Spec — EP-CAT-08](../../design/services-specs/catalog-service-spec.md), [Catalog Manager Scenarios (SCN-CM07, SCN-CM08)](../../design/scenarios/catalog-manager-scenarios.md), [Product persistence](03-product-entity-and-persistence.md)

## Acceptance Criteria
- [x] `DELETE /catalog/categories/{id}` returns `200 OK` when the category had no products and was deleted.
- [x] Category `id` not found → `404`.
- [x] One or more products (of **any** status, including `NOT_ACTIVE`) reference the category → `409 Conflict` ("Category has associated products. Reassign or remove all products before deletion.") and nothing is deleted.
- [x] A product created concurrently with the delete results in either a consistent `409`, or the product creation failing with `422`; the database never ends up with a product pointing at a missing category.
- [x] Tests: service unit tests and integration tests for `200`, `404`, `409` (with an active product and with an inactive product), and the FK-violation race mapped to `409`.

## Technical Notes / Constraints
- The count check gives a friendly message; the `ON DELETE RESTRICT` foreign key from [task 03](03-product-entity-and-persistence.md) is the real guard. Map `DataIntegrityViolationException` from the delete to the same `409`.
- Products are never hard-deleted, so "remove all products" in the message really means "reassign or … (there is no product delete)". Consider whether the wording matches reality and adjust it if the team agrees; keep the status code.
- The spec returns `200 OK` (no body) rather than `204`. Follow the spec.
- Authorization is added in [task 12](12-trusted-header-security.md); until then the endpoint is open (local use only).
- **Open for team decision:** the `409` message currently keeps the spec wording ("...Reassign or remove all products before deletion."), even though products cannot be removed. A more accurate option would be "Category has associated products. Reassign them to another category before deletion." Update the spec and the code together if the team agrees.
