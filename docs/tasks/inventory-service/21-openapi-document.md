---
status: TODO
service: inventory-service
---
# OpenAPI Document for Inventory

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Inventory. This task is independent of the Auth, Customer, Order and Delivery tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Inventory Spec — EP-INV-01..07, Responses and errors](../../design/services-specs/inventory-service-spec.md), [API Contracts — Inventory](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [ ] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Inventory API`, `bearerAuth` and `internalToken` schemes, `public` and `internal` groups, the D6 customizer, `Problem`, `ValidationProblem`, `InsufficientStockProblem` and `PackagingNotConfiguredProblem`), and `permitAll()` for the documentation paths with the security tests updated.
- [ ] The four internal operations carry `@InternalApi` and appear only in `internal.json`. Note that `GET /inventory/stock/{id}/packaging` is internal while `PUT` on the same path is public.
- [ ] DTOs are marked per D5: every never-null response field has `@Schema(requiredMode = REQUIRED)` (including the paging envelope and the packaging dimensions of EP-INV-05), and every request field that must be sent has a constraint annotation or `@Schema(requiredMode = REQUIRED)`.
- [ ] Operations document their success status and body (`204` without a body where the spec says so), and these error responses in addition to the customizer's `400`/`500`:

  | Operation | Auth | Explicit error responses |
  |---|---|---|
  | `GET /inventory/stock` (EP-INV-01) | `INVENTORY_WORKER` | `400 ValidationProblem`, `401`, `403` |
  | `POST /inventory/stock/{id}/adjust` (EP-INV-02) | `INVENTORY_WORKER` | `400 ValidationProblem`, `401`, `403`, `404`, `422 Problem` (write-off below zero), `503 Problem` with `Retry-After` |
  | `PUT /inventory/stock/{id}/packaging` (EP-INV-03) | `INVENTORY_WORKER` | `400 ValidationProblem`, `401`, `403`, `404`, `503 Problem` with `Retry-After` |
  | `GET /inventory/stock/{id}/readiness` (EP-INV-04, internal) | `internalToken` | `401`, `404`, `422 PackagingNotConfiguredProblem` |
  | `GET /inventory/stock/{id}/packaging` (EP-INV-05, internal) | `internalToken` | `401`, `404`, `422 PackagingNotConfiguredProblem` |
  | `POST /inventory/reservations` (EP-INV-06, internal) | `internalToken` | `400 ValidationProblem`, `401`, `409 Problem`, `422 InsufficientStockProblem` |
  | `POST /inventory/reservations/{orderId}/release` (EP-INV-07, internal) | `internalToken` | `401` |

  `401`/`403`/`404`/`409` without a schema name use `Problem`. The EP cards win where they differ; record any difference under *Deviations*.
- [ ] `OpenApiDocumentTest` compares `/v3/api-docs/public` and `/v3/api-docs/internal` with the committed `inventory-service/openapi/public.json` and `internal.json`, and rewrites them with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [ ] `inventory-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8084/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [ ] All existing Inventory tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it.
- The stock list's query parameters (`packagingConfigured`, `page`, `size`, `sortBy`) appear as individual parameters; use `@ParameterObject` if they are bound to an object.
- Do not change any endpoint's behaviour, path or status code.
