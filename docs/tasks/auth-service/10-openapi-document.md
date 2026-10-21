---
status: DONE
service: auth-service
---
# OpenAPI Document for Auth

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Auth. This task is independent of the Customer, Inventory, Order and Delivery tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Auth Spec — EP-AUTH-01..03, Error Format](../../design/services-specs/auth-service-spec.md), [API Contracts — Auth](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [x] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Auth API`, `bearerAuth` scheme only, the `public` group only, the D6 customizer, `Problem` and `ValidationProblem`), and `permitAll()` for the documentation paths with the security tests updated.
- [x] Auth has no internal endpoints: there is no `internal` group, no `@InternalApi` usage and no `internal.json`.
- [x] DTOs are marked per D5: every never-null response field has `@Schema(requiredMode = REQUIRED)` (`userId`, `token`), and every request field that must be sent has a constraint annotation or `@Schema(requiredMode = REQUIRED)`.
- [x] Operations document their success status and body, and these error responses in addition to the customizer's `400`/`500`:

  | Operation | Gateway auth | Explicit error responses |
  |---|---|---|
  | `POST /user/register` (EP-AUTH-01) | PUBLIC | `400 ValidationProblem`, `409 Problem` |
  | `POST /auth/login` (EP-AUTH-02) | PUBLIC | `400 ValidationProblem`, `401 Problem` |
  | `POST /user/non-customer` (EP-AUTH-03) | `ADMIN` | `400 ValidationProblem` (including an invalid `role`), `401`, `403`, `409` |

  The EP cards win where they differ; record any difference under *Deviations*.
- [x] `OpenApiDocumentTest` compares `/v3/api-docs/public` with the committed `auth-service/openapi/public.json` and rewrites it with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [ ] `auth-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8086/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [x] All existing Auth tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it. If the pilot's code doesn't fit Auth, adapt it minimally and note why.
- `timestamp` and `service` are optional in `Problem` precisely because Auth's framework-built errors (for example malformed JSON) carry neither. Don't change the error handler to fill them in this task.
- Passwords appear only in request schemas. Do not add `example` values that look like real credentials.
- Do not change any endpoint's behaviour, path or status code.

## Decisions (2026-10-21)
- Same run, branch and rules as the Catalog pilot ([task 26, *Decisions*](../catalog-service/26-openapi-document.md#decisions-2026-10-21)): springdoc 3.1.1, one commit per service, no `docs/design/` edits in this run, and one Compose check for all services at the end.

## Implementation Notes (2026-10-21)
- Copied from the pilot: the dependency, the `springdoc` block in `application.yml`, the `permitAll()` rule for the documentation paths, the Compose entries, `Problem`, `ValidationProblem` and `ErrorResponsesCustomizer` (only the package changed), and the two tests. `OpenApiConfig` is the pilot's minus the internal group, the `internalToken` scheme and the Catalog-only optional-authentication customizer; there is no `@InternalApi` class.
- Controllers: `@Tag` (`Authentication`, `Users`), `@Operation` per method (EP number; `Public.` or `Role: ADMIN.`), `@SecurityRequirement(name = BEARER_AUTH)` on `POST /user/non-customer` only, and the error responses of the task table.
- D5: `LoginResponse.token` and `RegisterUserResponse.userId` are `REQUIRED`. The request fields already have `@NotBlank`/`@NotNull`, so they are required in the document without extra marks. `CreateNonCustomerUserRequest` extends `RegisterUserRequest`; springdoc inlines the inherited `email` and `password`, so the schema lists all three fields as required. `role` got a description ("Any role except CUSTOMER"), because the enum also lists `CUSTOMER`. No `example` values on any request field.
- **Verified:** removing the `REQUIRED` mark from `LoginResponse.token` failed `OpenApiDocumentTest` with "public.json differs from the code. Regenerate with: ..."; restoring it passed. `OpenApiDisabledByDefaultTest`: `/v3/api-docs`, `/v3/api-docs/public`, `/swagger-ui.html` and `/swagger-ui/index.html` answer `404` with springdoc off. Read `openapi/public.json`: 3 operations, security only on `non-customer`, named problem schemas, no `[T, "null"]`. Full suite `mvnw.cmd clean test`: 17 test classes, 98 tests, all green.

## Deviations from spec / task
- **Pilot details carried over:** `@Tag` per controller, and the `default-produces-media-type` and `override-with-generic-response` properties (reasons in the Catalog task).
- **`400` for an unknown `role`.** The task says the `400` includes an invalid `role`. Two cases answer it differently, and both match `ValidationProblem`: the role `CUSTOMER` goes through `InvalidUserRoleException` (`errors.role`); a value outside the enum fails while the JSON is read and gets Spring's default problem (`type: about:blank`, no `errors`, `timestamp` or `service`). This is why those fields are optional in the schema. No behaviour changed.
- **Code vs. task table:** no other difference.
