---
status: DONE
service: customer-service
---
# OpenAPI Document for Customer

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Customer. This task is independent of the Auth, Inventory, Order and Delivery tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Customer Spec — EP-CUST-01..09, Cart Aggregate → Error conventions](../../design/services-specs/customer-service-spec.md), [API Contracts — Customer](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [x] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Customer API`, `bearerAuth` scheme only, the `public` group only, the D6 customizer, `Problem`, `ValidationProblem` and `CartRuleProblem`), and `permitAll()` for the documentation paths with the security tests updated.
- [x] Customer has no internal endpoints: there is no `internal` group, no `@InternalApi` usage and no `internal.json`.
- [x] DTOs are marked per D5. Cart item fields (`productId`, `quantity`) are required. Profile **response** fields stay unmarked, because a customer who never saved a profile gets all `null`. In the profile **request**, the names and `phone` are required and `defaultAddress` stays unmarked.
- [x] `CartRuleProblem` documents `violation` as an enum (`TOO_MANY_PRODUCTS`, `QUANTITY_LIMIT_EXCEEDED`, `DUPLICATE_PRODUCT`) and the optional detail properties `resultingProductCount`, `maxProducts`, `productIds`, `maxQuantity`, matching `CartRuleViolationException`.
- [x] All nine operations require `CUSTOMER` (`bearerAuth`; the description starts with `Role: CUSTOMER.`), document their success status and body, and declare `401` and `403` plus:

  | Operation | Explicit error responses besides `401`/`403` |
  |---|---|
  | `GET /customers/me/profile` (EP-CUST-01) | — |
  | `PUT /customers/me/profile` (EP-CUST-02) | `400 ValidationProblem` |
  | `GET /customers/cart` (EP-CUST-03) | — |
  | `POST /customers/cart/items` (EP-CUST-04) | `400 ValidationProblem`, `422 CartRuleProblem` |
  | `PATCH /customers/cart/items/{productId}` (EP-CUST-05) | `400 ValidationProblem`, `404 Problem`, `422 CartRuleProblem` |
  | `DELETE /customers/cart/items/{productId}` (EP-CUST-06) | — |
  | `PUT /customers/cart` (EP-CUST-07) | `400 ValidationProblem`, `422 CartRuleProblem` |
  | `POST /customers/cart/merge` (EP-CUST-08) | `400 ValidationProblem`, `422 CartRuleProblem` |
  | `DELETE /customers/cart` (EP-CUST-09) | — |

  The EP cards win where they differ; record any difference under *Deviations*.
- [x] `OpenApiDocumentTest` compares `/v3/api-docs/public` with the committed `customer-service/openapi/public.json` and rewrites it with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [x] `customer-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8087/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [x] All existing Customer tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it.
- Customer's malformed-JSON and type-mismatch errors already answer `ValidationProblem` with `errors` (`body` for invalid JSON), so the customizer's default `400` matches them.
- Do not change any endpoint's behaviour, path or status code.

## Decisions (2026-10-21)
- Same run, branch and rules as the Catalog pilot ([task 26, *Decisions*](../catalog-service/26-openapi-document.md#decisions-2026-10-21)): springdoc 3.1.1, one commit per service, no `docs/design/` edits in this run, and one Compose check for all services at the end.

## Implementation Notes (2026-10-21)
- Copied from the pilot: the dependency, the `springdoc` block in `application.yml`, the `permitAll()` rule, the Compose entries, `Problem`, `ValidationProblem`, `ErrorResponsesCustomizer` and the two tests (with Customer's `PostgresTestContainerConfig` and `KafkaTestContainerConfig`). `OpenApiConfig` has the `public` group and the `bearerAuth` scheme only; there is no `@InternalApi` class.
- New `CartRuleProblem`: `violation` (the `CartRuleViolation` enum, so the document lists `TOO_MANY_PRODUCTS`, `QUANTITY_LIMIT_EXCEEDED`, `DUPLICATE_PRODUCT`) is required. `resultingProductCount`, `maxProducts`, `productIds` and `maxQuantity` are optional, and each description says which violation sets it, as in `CartRuleViolationException`.
- Controllers: `@Tag` (`Profile`, `Cart`), and on all nine operations `@SecurityRequirement(name = BEARER_AUTH)`, a description starting with `Role: CUSTOMER.`, and `401`/`403` plus the error responses of the task table. The cart rules (10 products, quantity 1 to 10) are named in the descriptions of the operations that check them.
- D5: `CartItemResponse.productId` and `quantity` are `REQUIRED`. `ProfileResponse` stays unmarked (all `null` for a customer without a profile). The request records already have Bean Validation constraints, so `firstName`, `lastName`, `phone`, `items`, `productId` and `quantity` are required, and `defaultAddress` is optional, with no extra marks.
- **Verified:** removing a `REQUIRED` mark from `CartItemResponse` failed `OpenApiDocumentTest` ("public.json differs from the code. ..."); restoring it passed. `OpenApiDisabledByDefaultTest`: the documentation paths answer `404` with springdoc off. Read `openapi/public.json`: 9 operations, each with `bearerAuth`, cart answers as arrays of `CartItemResponse`, `CartRuleProblem` on every `422`, no `[T, "null"]`. Full suite `mvnw.cmd clean test`: 15 test classes, 130 tests, all green.
- **Compose check (2026-10-22):** with the whole stack up, `http://localhost:8087/swagger-ui.html` answers `200` and lists the public group; `/v3/api-docs/swagger-config` has `supportedSubmitMethods: []` (read-only); every runtime document equals the committed file as a JSON tree. The gateway answers `404` for `/v3/api-docs/public`. `k8s/` is unchanged.

## Deviations from spec / task
- **Pilot details carried over:** `@Tag` per controller, and the `default-produces-media-type` and `override-with-generic-response` properties (reasons in the Catalog task).
- **Nested request schema named `CartItemsRequestItem`.** Springdoc names the nested record `CartItemsRequest.Item` just `Item`, which is too generic for generated client types. `@Schema(name = "CartItemsRequestItem")` on the record fixes the name; the JSON shape is unchanged.
- **`400` on `DELETE /customers/cart/items/{productId}`.** The table lists no `400`, but a non-UUID `productId` answers one. The customizer adds it, as agreed for path-only operations in the pilot.
- **Code vs. task table:** no other difference.
