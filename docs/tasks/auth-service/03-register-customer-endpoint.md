---
status: TODO
service: auth-service
---
# EP-AUTH-01: Register Customer Endpoint

## Context
Unauthenticated customers need to create an account. Registration always creates a `CUSTOMER`; the role is never taken from the request body.
*References:* [Auth Service Spec — EP-AUTH-01](../../design/services-specs/auth-service-spec.md), [Customer Scenarios (SCN-C01, SCN-C02)](../../design/scenarios/customer-scenarios.md)

## Acceptance Criteria
- [ ] `POST /auth/register` accepts `{ email, password }` and returns `201 Created { userId }`.
- [ ] Request DTO is validated with Bean Validation: `email` non-empty and well-formed; `password` meets strength rules.
- [ ] Invalid input returns `400 Bad Request` with field-level error messages.
- [ ] Already registered email returns `409 Conflict` ("Email already in use").
- [ ] Password is hashed with BCrypt (`PasswordEncoder` bean) before persisting; the plain-text password is never stored or logged.
- [ ] Persisted user has `role = CUSTOMER`, a generated UUID, and `createdAt = now()`.
- [ ] Concurrent registrations with the same email result in one `201` and one `409` (not a `500`).
- [ ] Tests: service unit tests (hashing, duplicate, role) and controller/integration tests covering each edge case in the spec.

## Technical Notes / Constraints
- Password rules are "TBD during implementation" in the spec. Propose the rules (e.g., min 8 chars, at least one letter and one digit), record the decision in the docs, and implement them as a reusable custom validator or `@Pattern`.
- Race condition: two requests can both pass the `existsByEmail` check. Catch `DataIntegrityViolationException` from the unique constraint and map it to the same `409`.
- Use separate request/response DTOs; never expose the `User` entity or `passwordHash`.
- The response must not leak anything beyond `userId`.
