---
status: DONE
service: catalog-service
---
# EP-CAT-01: Create Product

## Context
A Catalog Manager creates a product. Catalog is the master of `productId`, so the UUID is generated here and later referenced by all other services. Products are always created `NOT_ACTIVE`, letting the manager prepare the listing before publishing it.

The spec's step 4 (publish `ProductCreated`) is deliberately **not** part of this task. It needs the message broker and is introduced in [task 11](11-product-created-publish-and-listen.md) (first version, right after the basic CRUD tasks) and hardened in [task 19](19-transactional-outbox.md). This task delivers everything else in the endpoint. Authorization is added in [task 12](12-trusted-header-security.md); until then the endpoint is open (local use only).
*References:* [Catalog Spec — EP-CAT-01](../../design/services-specs/catalog-service-spec.md), [Catalog Manager Scenarios (SCN-CM01)](../../design/scenarios/catalog-manager-scenarios.md), [Product persistence](03-product-entity-and-persistence.md)

## Acceptance Criteria
- [x] `POST /catalog/products` accepts `{ title, description, categoryId, basePrice }` and returns `201 Created { productId }`.
- [x] Request DTO validation → `400` with field errors: `title` and `description` non-blank (with maximum lengths), `categoryId` a valid UUID, `basePrice` present and `> 0` with at most the allowed number of decimal places.
- [x] `categoryId` not found in `categories` → `422` ("Category not found").
- [x] The persisted product has a generated UUID, `status = NOT_ACTIVE`, `stockStatus = OUT_OF_STOCK`. The client cannot set `status` or `stockStatus` in this request (unknown fields are ignored or rejected, not applied).
- [x] A category deleted concurrently between the existence check and the insert results in `422`, not `500` (the foreign key is the real guard).
- [x] Tests: service unit tests (defaults, missing category) and controller/integration tests for each edge case in the spec.

## Technical Notes / Constraints
- The `400` vs `422` split is intentional: bad field values are `400`, a well-formed but unknown `categoryId` is `422`.
- Keep the service method structured so that a later task can add "emit an event in the same transaction" without restructuring it. Do **not** add event-publishing code or Kafka dependencies yet.
- Use `BigDecimal` for `basePrice` in the DTO; make sure JSON numbers like `10.999` are rejected by the scale rule rather than silently rounded.
- `title` and `description` are trimmed before they are stored. The service has a marked spot where `ProductCreated` will be emitted in the same transaction (tasks 11 and 19).
- The FK race is tested by spying on `CategoryRepository.existsById` so that the pre-check passes for a missing category. The insert then fails on `fk_products_category` and returns `422`.
