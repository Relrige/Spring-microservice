---
status: DONE
service: catalog-service
---
# Consistent Error Handling

## Context
The spec uses `400`, `404`, `409`, `422` and `503` for the endpoints. They should share one error format and a small set of domain exceptions instead of each controller shaping its own response. This task builds the shared pieces; later endpoint tasks only throw the right exceptions.
*References:* [Catalog Spec](../../design/services-specs/catalog-service-spec.md), [Auth Service error handling task](../auth-service/05-error-handling-and-validation.md)

## Acceptance Criteria
- [x] A `@RestControllerAdvice` (extending `ResponseEntityExceptionHandler`) maps validation failures to `400` with a field-level `errors` map, malformed JSON to `400`, and type mismatches (e.g. a non-UUID `{id}`) to `400`.
- [x] Domain exceptions and their mapping:
  - `ProductNotFoundException`, `CategoryNotFoundException` → `404`
  - `CategoryNameAlreadyInUseException` → `409`
  - `CategoryHasProductsException` → `409`
  - `CategoryNotFoundForProductException` (target category of a product does not exist) → `422`
  - (added later, in [task 21](21-inventory-readiness-client.md)) `ProductActivationRejectedException` (carries a machine-readable `reason`) → `422`, `InventoryUnavailableException` → `503`
- [x] Unexpected exceptions return `500` with a generic message, no stack trace; the stack trace is logged server-side.
- [x] Error format is the RFC 9457 `ProblemDetail` shape used by the other services: `type`, `title`, `status`, `detail`, `timestamp`, `service`. (The `422` activation failure added in task 21 additionally carries `reason`.)
- [x] Tests cover each mapped exception through a controller slice test.

## Technical Notes / Constraints
- Reuse the exact response shape of `auth-service` so clients see one error format across the platform. The draft handler's `type` URIs (`https://voltstore.com/errors/...`) should be checked against what `auth-service` uses and aligned.
- Think about `400` vs `422` as the spec uses them: `400` = the request is malformed or violates field rules; `422` = the request is well-formed but refers to something that does not exist or cannot be done (missing category, activation not ready). Keep this distinction consistent in tests.
- The exceptions for features not implemented yet (`CategoryHasProductsException`, and `ProductActivationRejectedException` / `InventoryUnavailableException`, which belong to [task 21](21-inventory-readiness-client.md)) may be added together with the task that first throws them, as long as the mapping lives in this one advice.
- Security-related `401`/`403` responses are rendered by the entry point/handler from [task 12](12-trusted-header-security.md), not by this advice. They must use the same body shape.
- **Error types (decision):** `https://voltstore.com/errors/{validation | bad-request | not-found | conflict | unprocessable-entity | internal-error}`, the same base URI as `auth-service`. Framework-generated errors (`405`, `415`, ...) are enriched in `createResponseEntity` with a type derived from the status plus `timestamp` and `service`, so every error has the same shape.
- A JSON value of the wrong type (e.g. `"categoryId": "abc"`) is reported as a `400` field error in `errors`, like a Bean Validation failure. Syntactically broken JSON is a plain `400` without `errors`.
- `RequestValidationException` (`400` with `errors`) is thrown by `RequestValidator` for endpoints that must check existence before validating the body (tasks 06 and 09).
- **Constraint-name detection:** races are mapped by constraint name (`utils/ConstraintViolations`). PostgreSQL 18 words a blocked delete as `violates RESTRICT setting of foreign key constraint "..."`, which Hibernate cannot parse (the name comes back as `null`), so there is a fallback that matches the quoted name in the SQL error message.
