---
status: DONE
service: auth-service
---
# EP-AUTH-01: Register Customer Endpoint

## Context
Unauthenticated customers need to create an account. Registration always creates a `CUSTOMER`; the role is never taken from the request body.
*References:* [Auth Service Spec — EP-AUTH-01](../../design/services-specs/auth-service-spec.md), [Customer Scenarios (SCN-C01, SCN-C02)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [x] `POST /auth/register` accepts `{ email, password }` and returns `201 Created { userId }`.
- [x] Request DTO is validated with Bean Validation: `email` non-empty and well-formed; `password` meets strength rules.
- [x] Invalid input returns `400 Bad Request` with field-level error messages.
- [x] Already registered email returns `409 Conflict` ("Email already in use").
- [x] Password is hashed with BCrypt (`PasswordEncoder` bean) before persisting; the plain-text password is never stored or logged.
- [x] Persisted user has `role = CUSTOMER`, a generated UUID, and `createdAt = now()`.
- [x] Concurrent registrations with the same email result in one `201` and one `409` (not a `500`).
- [x] Tests: service unit tests (hashing, duplicate, role) and controller/integration tests covering each edge case in the spec.

## Technical Notes / Constraints
- Password rules are "TBD during implementation" in the spec. Decision: password must be 5 to 64 characters long (`@NotBlank` + `@Size(min = 5, max = 64)` on `RegisterUserRequest`); no complexity requirements for now. Email must be non-blank, well-formed (`@Email`) and at most 256 characters.
- Race condition: two requests can both pass the `existsByEmail` check. Catch `DataIntegrityViolationException` from the unique constraint and map it to the same `409`.
- Use separate request/response DTOs; never expose the `User` entity or `passwordHash`.
- The response must not leak anything beyond `userId`.
