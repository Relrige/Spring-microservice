---
status: TODO
service: catalog-service
---
# EP-CAT-09: Batch Get Products by IDs (Internal)

## Context
Order Service calls this endpoint during order submission to read current prices and to verify product `status` in one round trip. It is an `Internal` endpoint: it carries no user, is authenticated with `X-Internal-Token`, and is never routed by the gateway.

The caller (Order Service) is outside this service's scope. This task only provides the endpoint and its contract.
*References:* [Catalog Spec — EP-CAT-09](../../design/services-specs/catalog-service-spec.md), [Security Model](../../design/services-requirements/security-model.md), [Order Service declarative client task](../order-service/declarative-http-client.md)

## Acceptance Criteria
- [ ] `GET /catalog/products/batch-get?ids={uuid1,uuid2,...}` parses `ids` as a comma-separated list of UUIDs.
- [ ] Returns `200 OK [ { productId, title, basePrice, status } ]` for the products that exist, regardless of their status (the caller decides what to do with `NOT_ACTIVE`).
- [ ] Unknown IDs are simply absent from the array (no `notFound` field, decided with the team). If none exists → `200 OK` with `[]`.
- [ ] Missing/empty `ids` → `400`; a malformed UUID in the list → `400`.
- [ ] Duplicate IDs in the request do not produce duplicate entries.
- [ ] A maximum number of IDs per request is enforced (choose and document the limit) → `400` above it.
- [ ] Route rule `GET /catalog/products/batch-get` → `hasRole("SERVICE")`, declared before the public product-details rule. Anonymous → `401`; a user with any role (even `ADMIN`) → `403`; valid `X-Internal-Token` → success.
- [ ] Documentation: update [api-contracts.md](../../design/services-requirements/api-contracts.md) (it shows `[{productId, price}]`, spec says `basePrice` plus `title` and `status`) and note that the `notFound` list from the spec text was dropped on purpose.
- [ ] Tests: service tests; controller/integration tests for found / partially found / none found / empty / malformed / too many IDs; security tests for `401`/`403`/`SERVICE`.

## Technical Notes / Constraints
- Fetch with a single `IN (...)` query, not one query per ID.
- Contract drift to resolve later in Order Service: its existing `CatalogClient` calls `/products/batch` and expects the draft shape. The follow-up belongs to the order-service tasks; mention it in the PR description.
- This is an exception to "spec is the source of truth": the spec's `notFound` list and "`products` array" wording describe a wrapper object, while the agreed contract is a flat array. Record the agreed shape in `api-contracts.md` so both sides read the same document.
- Do not log the full ID list at `INFO` level.
