---
status: DONE
service: auth-service
---
# Consistent Error Handling

## Context
Both endpoints return `400`, `401`, and `409` responses. They should share one error format instead of each controller shaping its own.
*References:* [Auth Service Spec](../../design/services-specs/auth-service-spec.md), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] Add a `@RestControllerAdvice` that maps validation failures to `400` with a field-level error list.
- [x] Map domain exceptions (`EmailAlreadyInUseException` → `409`, `InvalidCredentialsException` → `401`) to responses.
- [x] Unexpected exceptions return `500` with a generic message and no stack trace or internals.
- [x] Error format follows the project's API contract conventions (check `api-contracts.md`; if none exist, use RFC 9457 `ProblemDetail` and document it).
- [x] Tests cover each mapped exception.

## Technical Notes / Constraints
- **Error format (decision):** `api-contracts.md` defines none, so the service uses RFC 9457 `ProblemDetail` (`application/problem+json`), the same shape as the other services. Every error has `type`, `title`, `status`, `detail`, `timestamp` and `service`. Validation errors (`400`) additionally carry `errors`, a map of field name to a list of messages.
- **Mapping:** validation failure → `400`; malformed JSON → `400` (inherited from `ResponseEntityExceptionHandler`); `EmailNotUniqueException` → `409`; `InvalidCredentialsException` → `401`; anything else → `500` with a generic message. Unexpected exceptions are logged server-side with their stack trace, but never exposed to the client.
- Malformed JSON bodies must also produce `400`, not `500`.
- Keep the `401` body fixed so it cannot be used to tell users apart.
