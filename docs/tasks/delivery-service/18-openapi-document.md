---
status: TODO
service: delivery-service
---
# OpenAPI Document for Delivery

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Delivery. This task is independent of the Auth, Customer, Inventory and Order tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Delivery Spec — EP-DEL-01..03, Responses and errors](../../design/services-specs/delivery-service-spec.md), [API Contracts — Delivery](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [ ] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Delivery API`, `bearerAuth` and `internalToken` schemes, `public` and `internal` groups, the D6 customizer, `Problem`, `ValidationProblem` and `TtnGenerationFailedProblem`), and `permitAll()` for the documentation paths with the security tests updated.
- [ ] The two internal operations carry `@InternalApi` and appear only in `internal.json`.
- [ ] DTOs are marked per D5: every never-null response field has `@Schema(requiredMode = REQUIRED)`. In the batch status items only `orderId` is required; `status`, `ttn` and `failureReason` stay unmarked (`status: null` when no shipment exists yet). The retry response's `ttn` is required.
- [ ] Operations document their success status and body (`204` without a body for the cancel), and these error responses in addition to the customizer's `400`/`500`:

  | Operation | Auth | Explicit error responses |
  |---|---|---|
  | `POST /delivery/orders/{id}/ttn/retry` (EP-DEL-03) | `INVENTORY_WORKER` | `401`, `403`, `404`, `409 Problem`, `424 TtnGenerationFailedProblem` |
  | `GET /delivery/shipments` (EP-DEL-01, internal) | `internalToken` | `400 ValidationProblem`, `401` |
  | `DELETE /delivery/shipments/{orderId}` (EP-DEL-02, internal) | `internalToken` | `401`, `409 Problem` |

  `401`/`403`/`404` without a schema name use `Problem`. The EP cards win where they differ; record any difference under *Deviations*.
- [ ] The carrier delivery report (EP-DEL-04) is a Java interface, not an HTTP endpoint, and does not appear in any document.
- [ ] `OpenApiDocumentTest` compares `/v3/api-docs/public` and `/v3/api-docs/internal` with the committed `delivery-service/openapi/public.json` and `internal.json`, and rewrites them with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [ ] `delivery-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8083/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [ ] All existing Delivery tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it.
- Delivery's malformed-JSON and type-mismatch errors already answer `ValidationProblem` with `errors`, so the customizer's default `400` matches them.
- Do not change any endpoint's behaviour, path or status code.
