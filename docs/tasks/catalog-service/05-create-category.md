---
status: DONE
service: catalog-service
---
# EP-CAT-06: Create Category

## Context
A Catalog Manager creates categories before assigning products to them.
*References:* [Catalog Spec — EP-CAT-06](../../design/services-specs/catalog-service-spec.md), [Catalog Manager Scenarios (SCN-CM05)](../../design/scenarios/catalog-manager-scenarios.md), [Category persistence](02-category-entity-and-persistence.md)

## Acceptance Criteria
- [x] `POST /catalog/categories` accepts `{ name }` and returns `201 Created { categoryId, name }`.
- [x] Request DTO is validated: `name` is non-blank (and has a maximum length matching the column) → otherwise `400` with field errors.
- [x] A category with the same `name` (ignoring case and surrounding whitespace) already exists → `409 Conflict` ("Category name already in use").
- [x] Concurrent requests with the same name result in one `201` and one `409` (not a `500`).
- [x] Separate request and response DTOs; the entity is never serialized.
- [x] Tests: service unit tests (duplicate, trimming, case-insensitive duplicate) and controller/integration tests for `201`, `400`, `409`.

## Technical Notes / Constraints
- Apply the name-normalization decision from [task 02](02-category-entity-and-persistence.md) in a single place.
- Race condition: two requests can both pass the pre-check. Catch `DataIntegrityViolationException` from the unique constraint and map it to the same `409`, as auth-service does for registration.
- Response field is `categoryId` (not `id`), as written in the spec.
- Authorization (`CATALOG_MANAGER` only) is added for this endpoint in [task 12](12-trusted-header-security.md). Until then the endpoint is open, so run it only locally.
