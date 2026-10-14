---
status: DONE
service: catalog-service
---
# EP-CAT-05: Get Product Details

## Context
A customer opens a single product page. Only `ACTIVE` products are visible; an inactive product must be indistinguishable from a missing one so the existence of unpublished products does not leak. Managers inspect inactive products through the listing (EP-CAT-04), not here.
*References:* [Catalog Spec — EP-CAT-05](../../design/services-specs/catalog-service-spec.md), [Customer Scenarios (SCN-C06)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [x] `GET /catalog/products/{id}` returns `200 OK { productId, title, description, categoryId, basePrice, stockStatus }` for an `ACTIVE` product.
- [x] Product does not exist → `404`.
- [x] Product exists but is `NOT_ACTIVE` → `404`, with a body identical to the not-found case (same title and detail).
- [x] This applies to **all** callers, including managers: the endpoint is the customer view.
- [x] `GET /catalog/products/{id}` is `permitAll()`, declared **after** the `batch-get` rule (see [task 12](12-trusted-header-security.md)).
- [x] Non-UUID `{id}` → `400`.
- [x] Tests: service tests for the three cases; controller tests for anonymous access, and a test proving `GET /catalog/products/batch-get` is **not** answered by this handler.

## Technical Notes / Constraints
- Do not use a message such as "product is inactive" in the `404`: the point is to not leak existence.
- `GET /catalog/products/batch-get` and `GET /catalog/products/{id}` share a prefix. Spring MVC prefers the literal path; the security matchers and the gateway need explicit care (the gateway spec requires UUID-constrained path variables for the same reason). Add a test for both.
- The response intentionally omits `status` (always `ACTIVE` here); use a dedicated DTO, not the manager DTO from task 09.
- **Implemented:** an inactive product throws the same `ProductNotFoundException` as a missing one ("Product with ID … not found"), so type, title and detail wording are identical; only the requested ID differs. Spring MVC prefers the literal `/batch-get` mapping; the security rule for it is declared before `/catalog/products/*`. Both are pinned by tests (controller slice and integration).
