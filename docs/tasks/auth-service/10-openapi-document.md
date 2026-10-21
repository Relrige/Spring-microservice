---
status: TODO
service: auth-service
---
# OpenAPI Document for Auth

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Auth. This task is independent of the Customer, Inventory, Order and Delivery tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Auth Spec — EP-AUTH-01..03, Error Format](../../design/services-specs/auth-service-spec.md), [API Contracts — Auth](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [ ] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Auth API`, `bearerAuth` scheme only, the `public` group only, the D6 customizer, `Problem` and `ValidationProblem`), and `permitAll()` for the documentation paths with the security tests updated.
- [ ] Auth has no internal endpoints: there is no `internal` group, no `@InternalApi` usage and no `internal.json`.
- [ ] DTOs are marked per D5: every never-null response field has `@Schema(requiredMode = REQUIRED)` (`userId`, `token`), and every request field that must be sent has a constraint annotation or `@Schema(requiredMode = REQUIRED)`.
- [ ] Operations document their success status and body, and these error responses in addition to the customizer's `400`/`500`:

  | Operation | Gateway auth | Explicit error responses |
  |---|---|---|
  | `POST /user/register` (EP-AUTH-01) | PUBLIC | `400 ValidationProblem`, `409 Problem` |
  | `POST /auth/login` (EP-AUTH-02) | PUBLIC | `400 ValidationProblem`, `401 Problem` |
  | `POST /user/non-customer` (EP-AUTH-03) | `ADMIN` | `400 ValidationProblem` (including an invalid `role`), `401`, `403`, `409` |

  The EP cards win where they differ; record any difference under *Deviations*.
- [ ] `OpenApiDocumentTest` compares `/v3/api-docs/public` with the committed `auth-service/openapi/public.json` and rewrites it with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [ ] `auth-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8086/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [ ] All existing Auth tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it. If the pilot's code doesn't fit Auth, adapt it minimally and note why.
- `timestamp` and `service` are optional in `Problem` precisely because Auth's framework-built errors (for example malformed JSON) carry neither. Don't change the error handler to fill them in this task.
- Passwords appear only in request schemas. Do not add `example` values that look like real credentials.
- Do not change any endpoint's behaviour, path or status code.
