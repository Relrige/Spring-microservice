---
status: TODO
service: order-service
---
# OpenAPI Document for Order

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Order. This task is independent of the Auth, Customer, Inventory and Delivery tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Order Spec — EP-ORD-01..08, Responses and errors](../../design/services-specs/order-service-spec.md), [API Contracts — Order](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [ ] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Order API`, `bearerAuth` scheme only, the `public` group only, the D6 customizer), and `permitAll()` for the documentation paths with the security tests updated.
- [ ] Order has no internal endpoints: there is no `internal` group, no `@InternalApi` usage and no `internal.json`.
- [ ] Problem schemas: `Problem`, `ValidationProblem`, `IneligibleItemsProblem`, `InsufficientStockProblem`, `OrderTotalTooLargeProblem`, `OrderStateProblem` and `PaymentDeclinedProblem`, with the extension fields from the conventions doc (matching `GlobalExceptionHandler`).
- [ ] DTOs are marked per D5: every never-null response field has `@Schema(requiredMode = REQUIRED)`, and every request field that must be sent has a constraint annotation or `@Schema(requiredMode = REQUIRED)`. Fields that can be null stay unmarked; check each against the code. Expected unmarked: order details `paidAt`, `reservationExpiresAt`, `ttn`, `deliveryStatus`; fulfillment-queue `deliveryStatus`, `ttn`, `failureReason`.
- [ ] Operations document their success status and body, and these error responses in addition to the customizer's `400`/`500`:

  | Operation | Role | Explicit error responses |
  |---|---|---|
  | `POST /orders/validate` (EP-ORD-01) | `CUSTOMER` | `400 ValidationProblem`, `401`, `403`, `422 IneligibleItemsProblem`, `503` with `Retry-After` |
  | `POST /orders` (EP-ORD-02) | `CUSTOMER` | `400 ValidationProblem`, `401`, `403`, `422` (`oneOf` `IneligibleItemsProblem`, `InsufficientStockProblem`, `OrderTotalTooLargeProblem`), `503` with `Retry-After` |
  | `POST /orders/{id}/pay` (EP-ORD-03) | `CUSTOMER` | `400 ValidationProblem`, `401`, `402 PaymentDeclinedProblem`, `403`, `404`, `409` (`oneOf` `OrderStateProblem`, `Problem`), `503` with `Retry-After` |
  | `GET /orders` (EP-ORD-04) | `CUSTOMER` | `400 ValidationProblem`, `401`, `403` |
  | `GET /orders/{id}` (EP-ORD-05) | `CUSTOMER` | `401`, `403`, `404` |
  | `DELETE /orders/{id}` (EP-ORD-06) | `CUSTOMER` | `401`, `403`, `404`, `409 OrderStateProblem`, `503` if the card allows it |
  | `GET /orders/fulfillment-queue` (EP-ORD-07) | `INVENTORY_WORKER` | `401`, `403` |
  | `POST /orders/{id}/ship` (EP-ORD-08) | `INVENTORY_WORKER` | `401`, `403`, `404`, `409` (`oneOf` `OrderStateProblem`, `Problem`) |

  `401`/`403`/`404` without a schema name use `Problem`. The EP cards and `GlobalExceptionHandler` win where they differ from this table; record any difference under *Deviations*.
- [ ] `OpenApiDocumentTest` compares `/v3/api-docs/public` with the committed `order-service/openapi/public.json` and rewrites it with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [ ] `order-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8082/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [ ] All existing Order tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it.
- Several problem schemas can answer the same status. Declare them with `oneOf` in the `@Schema` of one `@ApiResponse`; the `type` URI tells them apart at runtime.
- Card details appear only in the `pay` request schema. Do not add `example` values that look like real card numbers.
- Do not change any endpoint's behaviour, path or status code.
