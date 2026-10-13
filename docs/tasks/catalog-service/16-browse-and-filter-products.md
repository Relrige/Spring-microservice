---
status: DONE
service: catalog-service
---
# EP-CAT-04: Browse and Filter Products

## Context
The product listing serves two audiences with one endpoint: customers (and anonymous visitors) see only `ACTIVE` products; Catalog Managers see everything and may filter by `status`. `stockStatus` comes from the stored read-model column, so no call to Inventory is made on this path.
*References:* [Catalog Spec — EP-CAT-04](../../design/services-specs/catalog-service-spec.md), [API Contracts — Catalog](../../design/services-requirements/api-contracts.md), [Customer Scenarios (SCN-C05)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [x] `GET /catalog/products` accepts optional `search`, `categoryId`, `minPrice`, `maxPrice`, `sortBy`, `status`, `page`, `size`.
- [x] Response: `200 OK { content: [ { productId, title, basePrice, categoryId, status, stockStatus } ], page, totalElements }`, reusing the paging envelope from [task 13](13-list-categories.md).
- [x] **Visibility by caller:** anonymous and `CUSTOMER` (and any role other than manager) → only `ACTIVE` products; `CATALOG_MANAGER` → all products. The `status` filter applies only for managers; for other callers it is ignored (never exposes `NOT_ACTIVE` products).
- [x] `search` matches `title` or `description`, case-insensitive.
- [x] `minPrice` > `maxPrice` → `400`. Invalid `status`, `sortBy`, `page`, or `size` → `400`.
- [x] Unknown `categoryId` → `200` with empty `content` (not `404`). No match → `200` with empty `content`.
- [x] `GET /catalog/products` is `permitAll()`; the caller's role is read from the `Authentication` produced by the trusted-header filter, not from the raw header in the controller.
- [x] Tests: repository/specification tests on a real PostgreSQL for every filter and combination; controller tests for anonymous, customer, and manager visibility; paging and sorting tests.

## Technical Notes / Constraints
- **`sortBy` format is not defined in the spec.** Decide and document it (suggested: `field` or `field,direction`, such as `basePrice,asc`) and accept only a whitelist (for example `title`, `basePrice`), never arbitrary property names. Define the default sort so paging is deterministic (add `id` as a tiebreaker).
- Consider JPA `Specification` (or query-by-parameters) to compose optional filters without a combinatorial number of repository methods; compare it with a hand-written JPQL query with `:param IS NULL OR ...`. Explain the choice briefly.
- Escape `%` and `_` in `search` (same rule as in [task 13](13-list-categories.md)). `ILIKE '%…%'` cannot use a plain B-tree index; for this project size that is acceptable, but mention it as a known limit.
- Do not issue one query per product (N+1) to resolve categories: the response only exposes `categoryId`.
- Role-based visibility is business logic of this service, not a security-chain rule. Keep it in the service layer and unit-test it with a role parameter, so the logic is testable without HTTP.
- **`sortBy` (decision):** `field` or `field,direction`; field `title` or `basePrice` (whitelist), direction `asc` (default) or `desc`, case-insensitive. Without `sortBy` the order is `title`. `id` is always appended as a tiebreaker.
- **Query building (decision):** JPA `Specification`s (`repositories/ProductSpecifications`) combined with `Specification.allOf`. Each optional filter is added only when present, which reads better than one JPQL query full of `:param IS NULL OR …` and needs no repository method per combination. Search escapes `%`/`_` with Spring Data's `EscapeCharacter`. Known limit: `LIKE '%…%'` cannot use a B-tree index (full scan).
- **Visibility:** `ProductService.visibleStatus(requestedStatus, callerRole)`, unit-tested per role. The controller takes the role from `@AuthenticationPrincipal AuthenticatedUser` (null for anonymous and for service calls), never from the raw header. `minPrice > maxPrice` and a bad `sortBy` are service checks reported as `400` field errors; `minPrice`/`maxPrice` must also be `>= 0`.
