---
status: DONE
service: order-service
---
# OpenAPI Document for Order

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Order. This task is independent of the Auth, Customer, Inventory and Delivery tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Order Spec — EP-ORD-01..08, Responses and errors](../../design/services-specs/order-service-spec.md), [API Contracts — Order](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [x] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Order API`, `bearerAuth` scheme only, the `public` group only, the D6 customizer), and `permitAll()` for the documentation paths with the security tests updated.
- [x] Order has no internal endpoints: there is no `internal` group, no `@InternalApi` usage and no `internal.json`.
- [x] Problem schemas: `Problem`, `ValidationProblem`, `IneligibleItemsProblem`, `InsufficientStockProblem`, `OrderTotalTooLargeProblem`, `OrderStateProblem` and `PaymentDeclinedProblem`, with the extension fields from the conventions doc (matching `GlobalExceptionHandler`).
- [x] DTOs are marked per D5: every never-null response field has `@Schema(requiredMode = REQUIRED)`, and every request field that must be sent has a constraint annotation or `@Schema(requiredMode = REQUIRED)`. Fields that can be null stay unmarked; check each against the code. Expected unmarked: order details `paidAt`, `reservationExpiresAt`, `ttn`, `deliveryStatus`; fulfillment-queue `deliveryStatus`, `ttn`, `failureReason`.
- [x] Operations document their success status and body, and these error responses in addition to the customizer's `400`/`500`:

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
- [x] `OpenApiDocumentTest` compares `/v3/api-docs/public` with the committed `order-service/openapi/public.json` and rewrites it with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [x] `order-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8082/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [x] All existing Order tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it.
- Several problem schemas can answer the same status. Declare them with `oneOf` in the `@Schema` of one `@ApiResponse`; the `type` URI tells them apart at runtime.
- Card details appear only in the `pay` request schema. Do not add `example` values that look like real card numbers.
- Do not change any endpoint's behaviour, path or status code.

## Decisions (2026-10-21)
- Same run, branch and rules as the Catalog pilot ([task 26, *Decisions*](../catalog-service/26-openapi-document.md#decisions-2026-10-21)): springdoc 3.1.1, one commit per service, no `docs/design/` edits in this run, and one Compose check for all services at the end.

## Implementation Notes (2026-10-22)
- Copied from the pilot: the dependency, the `springdoc` block, the `permitAll()` rule, the Compose entries, `Problem`, `ValidationProblem`, `ErrorResponsesCustomizer` and the two tests (with Order's `TestcontainersConfiguration` and `KafkaTestContainerConfig`). `OpenApiConfig` has the `public` group and `bearerAuth` only; there is no `@InternalApi` class.
- Problem records, each mirroring a `GlobalExceptionHandler` method, with the extension field required: `IneligibleItemsProblem` (`ineligibleItems`, items of the existing `IneligibleItem` with its `NOT_ACTIVE | INSUFFICIENT_STOCK` enum), `InsufficientStockProblem` (`insufficientItems`), `OrderTotalTooLargeProblem` (`maxTotal`), `OrderStateProblem` (`orderStatus`, the `OrderStatus` enum) and `PaymentDeclinedProblem` (`reason`; the Payment adapter falls back to `declined`, so it is never null).
- Statuses with several possible bodies use `@Schema(oneOf = …)`: the `422` of `POST /orders` (three schemas), and the `409` of `pay` and `ship` (`OrderStateProblem` or `Problem`). Each description says which case answers which schema; at runtime the `type` URI tells them apart.
- Controllers: `@Tag` (`Orders`, `Fulfillment`), `@SecurityRequirement(name = BEARER_AUTH)` and a description starting with the role on all eight operations, `Retry-After` on every `503`, the `Location` header on the `201` of `POST /orders`, `202` for the cancel and `204` for the soft check. The history's `page`/`size` got parameter descriptions. No `example` values anywhere, so there are no card-like numbers in the document.
- D5: every response field that is never null is `REQUIRED`, checked against the code and the `orders` table (see *Deviations* for `reservationExpiresAt` and the queue's `paidAt`). Unmarked: details `paidAt`, `ttn`, `deliveryStatus`; queue `deliveryStatus`, `ttn`, `failureReason`. Request records already carry Bean Validation constraints.
- **Verified:** removing a `REQUIRED` mark from `PaidOrderResponse` failed `OpenApiDocumentTest` ("public.json differs from the code. ..."); restoring it passed. `OpenApiDisabledByDefaultTest`: the documentation paths answer `404` with springdoc off. Read `openapi/public.json`: 8 operations, all with `bearerAuth`, the `oneOf` lists as above, nested schemas with distinct names, no `[T, "null"]`, no `example`. Full suite `mvnw.cmd clean test`: 36 test classes, 336 tests, all green.
- **Compose check (2026-10-22):** with the whole stack up, `http://localhost:8082/swagger-ui.html` answers `200` and lists the public group; `/v3/api-docs/swagger-config` has `supportedSubmitMethods: []` (read-only); every runtime document equals the committed file as a JSON tree. The gateway answers `404` for `/v3/api-docs/public`. `k8s/` is unchanged.
  Order and Delivery needed a local workaround for the check, which is not part of this change: the local `.env` files of both lack `AUTH_INTERNAL_TOKEN`, and the local Order database volume holds an older `orders` table, so Flyway V1 fails. A temporary Compose override supplied the token and a fresh volume.

## Deviations from spec / task
- **Pilot details carried over:** `@Tag` per controller, and the `default-produces-media-type` and `override-with-generic-response` properties (reasons in the Catalog task).
- **`503` on `POST /orders/{id}/ship` (EP-ORD-08).** The task table lists `401`, `403`, `404`, `409` only. The EP card ("Delivery unavailable → `503`") and `FulfillmentService.ship` (a `DependencyUnavailableException` from the Delivery lookup) say it can also answer `503` with `Retry-After`. The code and the card win, so the operation documents `503` with the header.
- **`reservationExpiresAt` in the order details is required.** The task expects it unmarked, but `orders.reservation_expires_at` is `NOT NULL` and is set when the order is placed, so per D5 it carries `REQUIRED` (in `OrderDetailsResponse` and `PlacedOrderResponse`). `paidAt`, `ttn` and `deliveryStatus` stay unmarked. In the fulfillment queue, `paidAt` is required: the queue only holds paid orders, and a `CHECK` constraint guarantees `paid_at` for them. `deliveryStatus`, `ttn` and `failureReason` stay unmarked, as expected.
- **Explicit schema names for nested records.** `OrderDetailsResponse` and `FulfillmentQueueResponse` both nest records called `Item` and `Delivery`. Springdoc would give each pair one shared name, and one shape would overwrite the other in the document. They are named `OrderDetailsItem`, `OrderDetailsDelivery`, `FulfillmentQueueEntry`, `FulfillmentQueueItem` and `FulfillmentQueueDelivery`; the JSON shapes are unchanged.
- **One `409` schema on `DELETE /orders/{id}`.** "Cancellation already in progress" is `CancellationInProgressException`, a subclass of `InvalidOrderStateException`, so it also carries `orderStatus` (`CANCELLATION_REQUESTED`). `OrderStateProblem` alone therefore covers every `409` of the cancel, as the table says. No `503` either: the card has none, and saga failures answer `202` with `CANCELLATION_REQUESTED`.
- **`401` on `POST /orders` has a second cause.** Besides a missing token, `MissingCustomerEmailException` answers `401` when the token carries no email (the order needs it for notifications). The `401` description names both.
- **`400` on the path-only operations** (`GET` and `DELETE /orders/{orderId}`, `ship`): the table lists none, but the spec says a non-UUID path variable answers `400`; the customizer adds it, as agreed in the pilot.
