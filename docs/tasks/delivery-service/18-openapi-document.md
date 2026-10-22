---
status: DONE
service: delivery-service
---
# OpenAPI Document for Delivery

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Delivery. This task is independent of the Auth, Customer, Inventory and Order tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Delivery Spec — EP-DEL-01..03, Responses and errors](../../design/services-specs/delivery-service-spec.md), [API Contracts — Delivery](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [x] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Delivery API`, `bearerAuth` and `internalToken` schemes, `public` and `internal` groups, the D6 customizer, `Problem`, `ValidationProblem` and `TtnGenerationFailedProblem`), and `permitAll()` for the documentation paths with the security tests updated.
- [x] The two internal operations carry `@InternalApi` and appear only in `internal.json`.
- [x] DTOs are marked per D5: every never-null response field has `@Schema(requiredMode = REQUIRED)`. In the batch status items only `orderId` is required; `status`, `ttn` and `failureReason` stay unmarked (`status: null` when no shipment exists yet). The retry response's `ttn` is required.
- [x] Operations document their success status and body (`204` without a body for the cancel), and these error responses in addition to the customizer's `400`/`500`:

  | Operation | Auth | Explicit error responses |
  |---|---|---|
  | `POST /delivery/orders/{id}/ttn/retry` (EP-DEL-03) | `INVENTORY_WORKER` | `401`, `403`, `404`, `409 Problem`, `424 TtnGenerationFailedProblem` |
  | `GET /delivery/shipments` (EP-DEL-01, internal) | `internalToken` | `400 ValidationProblem`, `401` |
  | `DELETE /delivery/shipments/{orderId}` (EP-DEL-02, internal) | `internalToken` | `401`, `409 Problem` |

  `401`/`403`/`404` without a schema name use `Problem`. The EP cards win where they differ; record any difference under *Deviations*.
- [x] The carrier delivery report (EP-DEL-04) is a Java interface, not an HTTP endpoint, and does not appear in any document.
- [x] `OpenApiDocumentTest` compares `/v3/api-docs/public` and `/v3/api-docs/internal` with the committed `delivery-service/openapi/public.json` and `internal.json`, and rewrites them with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [x] `delivery-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8083/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [x] All existing Delivery tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it.
- Delivery's malformed-JSON and type-mismatch errors already answer `ValidationProblem` with `errors`, so the customizer's default `400` matches them.
- Do not change any endpoint's behaviour, path or status code.

## Decisions (2026-10-21)
- Same run, branch and rules as the Catalog pilot ([task 26, *Decisions*](../catalog-service/26-openapi-document.md#decisions-2026-10-21)): springdoc 3.1.1, one commit per service, no `docs/design/` edits in this run, and one Compose check for all services at the end.

## Implementation Notes (2026-10-22)
- Copied from the pilot: the dependency, the `springdoc` block, the `permitAll()` rule, the Compose entries, `OpenApiConfig` (both groups, without Catalog's optional-authentication customizer), `@InternalApi`, `Problem`, `ValidationProblem`, `ErrorResponsesCustomizer` and the two tests (with Delivery's `PostgresTestContainerConfig` and `KafkaTestContainerConfig`).
- New `TtnGenerationFailedProblem` with `reason` required: every `TtnAttemptResult.Failure` carries a reason (from Inventory's or the carrier's error, or a fixed code).
- `@InternalApi` on `getShipmentStatuses` and `cancelShipment`, so they are only in `internal.json`; `retryTtnGeneration` is the only public operation (`Role: INVENTORY_WORKER.`, `bearerAuth`). The carrier delivery report (EP-DEL-04) is a Java interface with no controller, so it appears in neither document.
- D5: `RetryTtnResponse.ttn` and `ShipmentStatusResponse.orderId` are `REQUIRED`; `status`, `ttn` and `failureReason` of the batch items stay unmarked (`null` without a shipment).
- **Verified:** removing the `REQUIRED` mark from `RetryTtnResponse.ttn` failed `OpenApiDocumentTest` ("public.json differs from the code. ..."); restoring it passed. `OpenApiDisabledByDefaultTest`: the documentation paths answer `404` with springdoc off. Read both documents: 1 public and 2 internal operations, `424 TtnGenerationFailedProblem` on the retry, `204` without a body for the cancel, no `[T, "null"]`. Full suite `mvnw.cmd clean test`: 20 test classes, 164 tests, all green.
- **Compose check (2026-10-22):** with the whole stack up, `http://localhost:8083/swagger-ui.html` answers `200` and lists both groups; `/v3/api-docs/swagger-config` has `supportedSubmitMethods: []` (read-only); every runtime document equals the committed file as a JSON tree. The gateway answers `404` for `/v3/api-docs/public`. `k8s/` is unchanged.
  Order and Delivery needed a local workaround for the check, which is not part of this change: the local `.env` files of both lack `AUTH_INTERNAL_TOKEN`, and the local Order database volume holds an older `orders` table, so Flyway V1 fails. A temporary Compose override supplied the token and a fresh volume.

## Deviations from spec / task
- **Pilot details carried over:** `@Tag` per controller, and the `default-produces-media-type` and `override-with-generic-response` properties (reasons in the Catalog task).
- **`orderIds` documented as required.** The controller binds it with `required = false` and `ShipmentService` answers `400` (`errors.orderIds`) when it is missing or empty, so the parameter is required in practice. `@Parameter(required = true)` makes the document say what the endpoint does; the binding is unchanged, so the error stays the service's own `400` with `errors`.
- **`400` on the path-only operations** (cancel and retry): the table lists none, but a non-UUID path value answers `400` (spec: "a non-UUID path or query value"); the customizer adds it, as agreed in the pilot.
- **Code vs. task table:** no other difference.
