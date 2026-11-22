---
status: DONE
service: auth-service
---
# DTOs as Java Records

## Context
The course rubric asks for "DTOs as Java Records", and the other services already use records. Auth's `dto` package still held Lombok `@Data` classes. Converting them gives the project one code style for DTOs. The HTTP contract and the OpenAPI document must not change.
*References:* [Auth Spec](../../design/services-specs/auth-service-spec.md), [OpenAPI Conventions](../../design/services-requirements/openapi-conventions.md), [task 10](10-openapi-document.md)

## Acceptance Criteria
- [x] `LoginRequest`, `LoginResponse`, `RegisterUserRequest`, `RegisterUserResponse` and `CreateNonCustomerUserRequest` are records with the same names, packages, JSON field names and order, validation annotations and `@Schema` annotations.
- [x] `CreateNonCustomerUserRequest` no longer extends `RegisterUserRequest`; it is a standalone record that repeats the `email` and `password` annotations exactly, followed by `role`.
- [x] All getter and setter uses of these DTOs in main and test code use record accessors and canonical constructors.
- [x] `openapi/public.json` is byte-identical after regeneration.
- [x] The full test suite passes.

## Decisions (2026-11-22)
1. All DTOs become records with an unchanged HTTP contract; Lombok stays for entities and other classes.
2. Records cannot extend classes, so `CreateNonCustomerUserRequest` is a standalone record with all three fields instead of inheriting from `RegisterUserRequest`.
3. Work is done on the branch `auth-service/dto-records`.

## Implementation Notes (2026-11-22)
- No DTO had a setter used after binding, so no logic had to move; `AuthService` and `UserService` only switched to accessors.
- **Verified:** `./mvnw -B test` runs 97 tests, 0 failures, 0 errors, 0 skipped. `OpenApiDocumentTest` passes, and regenerating with `-Dopenapi.update=true` rewrote `openapi/public.json` with no `git diff`, so the document is byte-identical.

## Deviations from spec / task
None.
