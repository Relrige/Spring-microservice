---
status: TODO
service: customer-service
---
# OpenAPI Document for Customer

## Context
Every service with gateway-routed endpoints publishes an OpenAPI 3.1 document. A test generates it and the result is committed, so the frontend can generate its types without a running stack (frontend backlog BL-04, agreed 2026-10-21). The pattern was set by the Catalog pilot. **Prerequisite:** [catalog task 26](../catalog-service/26-openapi-document.md) is `DONE`. Copy its `openapi` package, configuration, security rule, test and Compose entries, and adapt them to Customer. This task is independent of the Auth, Inventory, Order and Delivery tasks.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Customer Spec — EP-CUST-01..09, Cart Aggregate → Error conventions](../../design/services-specs/customer-service-spec.md), [API Contracts — Customer](../../design/services-requirements/api-contracts.md), [Catalog pilot](../catalog-service/26-openapi-document.md)

## Acceptance Criteria
- [ ] The building blocks from the pilot are copied and adapted: dependency (same springdoc version as Catalog), `application.yml` properties, the `openapi` package (title `Customer API`, `bearerAuth` scheme only, the `public` group only, the D6 customizer, `Problem`, `ValidationProblem` and `CartRuleProblem`), and `permitAll()` for the documentation paths with the security tests updated.
- [ ] Customer has no internal endpoints: there is no `internal` group, no `@InternalApi` usage and no `internal.json`.
- [ ] DTOs are marked per D5. Cart item fields (`productId`, `quantity`) are required. Profile **response** fields stay unmarked, because a customer who never saved a profile gets all `null`. In the profile **request**, the names and `phone` are required and `defaultAddress` stays unmarked.
- [ ] `CartRuleProblem` documents `violation` as an enum (`TOO_MANY_PRODUCTS`, `QUANTITY_LIMIT_EXCEEDED`, `DUPLICATE_PRODUCT`) and the optional detail properties `resultingProductCount`, `maxProducts`, `productIds`, `maxQuantity`, matching `CartRuleViolationException`.
- [ ] All nine operations require `CUSTOMER` (`bearerAuth`; the description starts with `Role: CUSTOMER.`), document their success status and body, and declare `401` and `403` plus:

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
- [ ] `OpenApiDocumentTest` compares `/v3/api-docs/public` with the committed `customer-service/openapi/public.json` and rewrites it with `-Dopenapi.update=true`. Both directions are checked by hand once.
- [ ] `customer-service` in `docker-compose.yml` gets the two `SPRINGDOC_*` entries; `http://localhost:8087/swagger-ui.html` works and is read-only. Kubernetes is unchanged.
- [ ] All existing Customer tests still pass.

## Technical Notes / Constraints
- All decisions in the conventions doc are settled; follow the pilot's code shape rather than redesigning it.
- Customer's malformed-JSON and type-mismatch errors already answer `ValidationProblem` with `errors` (`body` for invalid JSON), so the customizer's default `400` matches them.
- Do not change any endpoint's behaviour, path or status code.
