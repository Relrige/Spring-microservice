---
status: TODO
service: auth-service
---
# Consistent Error Handling

## Context
Both endpoints return `400`, `401`, and `409` responses. They should share one error format instead of each controller shaping its own.
*References:* [Auth Service Spec](../../design/services-specs/auth-service-spec.md), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] Add a `@RestControllerAdvice` that maps validation failures to `400` with a field-level error list.
- [ ] Map domain exceptions (`EmailAlreadyInUseException` → `409`, `InvalidCredentialsException` → `401`) to responses.
- [ ] Unexpected exceptions return `500` with a generic message and no stack trace or internals.
- [ ] Error format follows the project's API contract conventions (check `api-contracts.md`; if none exist, use RFC 9457 `ProblemDetail` and document it).
- [ ] Tests cover each mapped exception.

## Technical Notes / Constraints
- Malformed JSON bodies must also produce `400`, not `500`.
- Keep the `401` body fixed so it cannot be used to tell users apart.
