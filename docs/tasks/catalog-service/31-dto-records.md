---
status: DONE
service: catalog-service
---
# DTOs as Java Records

## Context
The course rubric asks for "DTOs as Java Records". Every class in Catalog's `dto` package was a Lombok `@Data` class (some with `@AllArgsConstructor`), while the other services already use records. Converting them gives the project one code style for DTOs. The HTTP contract must not change.
*References:* [OpenAPI conventions](../../design/services-requirements/openapi-conventions.md), [Catalog spec](../../design/services-specs/catalog-service-spec.md)

## Acceptance Criteria
- [x] All 11 DTOs in `catalog/dto` are records: `BatchProductResponse`, `CategoryRequest`, `CategoryResponse`, `CategorySearchQuery`, `CreateProductResponse`, `PageResponse<T>`, `ProductRequest`, `ProductResponse`, `ProductSearchQuery`, `ProductStatusRequest`, `ProductSummaryResponse`.
- [x] JSON field names and order, Bean Validation annotations, OpenAPI annotations (`@Schema`, `@Parameter`) and comments are unchanged, now on the record components.
- [x] The query objects bound with `@Valid @ModelAttribute @ParameterObject` (`CategorySearchQuery`, `ProductSearchQuery`) bind through the canonical constructor; `ids=a,b` and `ids=a&ids=b` still work, and every parameter stays optional.
- [x] Getter and setter uses in main and test code are replaced by accessors and constructor calls; entities keep Lombok.
- [x] `openapi/public.json` and `openapi/internal.json` are byte-identical.
- [x] The full test suite passes.

## Decisions (2026-11-22)
1. **Records with an unchanged contract.** Each DTO becomes a `record` with its fields as components, in the same order and with the same annotations; static factories (`from(...)`) and the `IMAGE_URL_PATTERN` constant stay in the record body.
2. **No inheritance to flatten.** No Catalog DTO extends another, so no standalone-record rewrite was needed. No DTO had a mutable usage (normalization after binding) that a record cannot express; the services already normalize values (trimming, category name normalization) themselves.
3. **Branch** `catalog-service/dto-records`.

## Implementation Notes (2026-11-22)
- `ProductRequest`'s `@Pattern` references the constant as `ProductRequest.IMAGE_URL_PATTERN`, since it now sits in the record header.
- Tests build requests and queries with the canonical constructor (`null` for unset components) instead of setters.
- **Verified:** `./mvnw -B test` — 291 tests, 0 failures, 0 errors, 0 skipped. OpenAPI documents regenerated from the running context with `-Dtest=OpenApiDocumentTest -Dopenapi.update=true` before and after the change; `git diff -- catalog-service/openapi` is empty both times, and `OpenApiDocumentTest` passes in the full run.

## Deviations from spec / task
None.
