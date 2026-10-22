---
id: BL-04
title: OpenAPI description for every routed service
status: In progress
importance: Important
services: [auth-service, catalog-service, customer-service, order-service, inventory-service, delivery-service]
blocks: —
created: 2026-10-20
---

# BL-04: OpenAPI description for every routed service

## Problem

The API contracts exist only as Markdown specs. The frontend writes its TypeScript request and response types by hand from them, so a backend change can silently break the frontend, and nothing checks that the specs and the running services agree.

## Current behaviour

*As of 2026-10-20, before the backend work; see* Delivered *below for the state now.*

- No service depends on springdoc or publishes `/v3/api-docs` (checked: no `springdoc` in any `pom.xml`).
- Contracts: `../../../docs/design/services-requirements/api-contracts.md` and the service specs.
- All services use Spring Boot 4.1.1.

## Proposed change

*The original request (2026-10-20). The backend decided differently on several points; see* Delivered.

1. Add springdoc-openapi (the release line that supports Spring Boot 4.1) to every service with gateway-routed endpoints: Auth, Catalog, Customer, Order, Inventory, Delivery. Payment and Notification are optional (no routed endpoints).
2. Expose the document on the **management port** (or another non-routed path), so it stays off the public gateway, consistent with the trust boundary. The frontend build reads it from the services' host ports in Compose.
3. Describe what the frontend relies on: request/response schemas, status codes per operation, and the problem-details extensions (`errors`, `violation`, `reason`, `ineligibleItems`, `insufficientItems`, `maxTotal`) as named schemas.
4. Keep internal endpoints in a separate group (or tagged), so it is clear which operations are public.

## Delivered (2026-10-22)

Implemented on the branch `springdoc` (PR [Relrige/Spring-microservice#20](https://github.com/Relrige/Spring-microservice/pull/20), not merged yet). It becomes `Done` once the PR is merged and the frontend has switched to the generated types. The binding rules are in [`openapi-conventions.md`](../../../docs/design/services-requirements/openapi-conventions.md) (decisions D1–D9). The backend tasks are `../../../docs/tasks/catalog-service/26-openapi-document.md` (pilot), then Auth 10, Customer 11, Inventory 21, Order 18 and Delivery 18.

### Differences from the request

| Requested | Delivered | What it means for the frontend |
|---|---|---|
| Serve the document on the management port; the frontend build reads it from the services' host ports in Compose | **Committed files.** A backend test generates each document in-process and compares it with `<service>/openapi/public.json`; a drift fails CI. No port, no running stack needed. | Generate types **from the files in the repository**, never from a running service: `openapi-typescript ../catalog-service/openapi/public.json`. Regenerate whenever those files change (they change in the same commit as the backend code). |
| A runtime endpoint off the gateway | `/v3/api-docs/public` and read-only Swagger UI exist **only in Docker Compose**, on each service's normal host port (`http://localhost:8081/swagger-ui.html` for Catalog, and the other ports listed in the conventions doc). They are **off in Kubernetes**, and the gateway never routes them. | Swagger UI is for people reading the API. Do not call `/v3/api-docs` from the app or from the build. "Try it out" is disabled; manual calls go through the gateway. |
| Internal endpoints separate | Internal operations are in a separate file, `<service>/openapi/internal.json` (Catalog, Inventory, Delivery), with the `internalToken` scheme. | Use **only `public.json`**. Never generate from `internal.json`: those operations are not reachable through the gateway. |
| Schemas for request and response bodies | **Optional unless explicitly required (D5).** A property is in `required` only if it is never missing and never `null`. Every other property is optional, and nullability is not encoded (no `T \| null`). | Generated optional fields are `field?: T`, but the service may also send `null`. Treat `undefined` and `null` alike (`?? fallback`, `== null`, never `=== undefined`). Example: Customer's profile response is all optional, because a customer without a saved profile gets every field as `null`. |
| Problem extensions as named schemas | Named schemas with media type `application/problem+json`: `Problem`, `ValidationProblem` (`errors`), `ActivationRejectedProblem` and `PaymentDeclinedProblem` (`reason`), `CartRuleProblem` (`violation` enum plus detail fields), `IneligibleItemsProblem`, `InsufficientStockProblem`, `OrderTotalTooLargeProblem` (`maxTotal`), `OrderStateProblem` (`orderStatus`), `TtnGenerationFailedProblem`. | Pick the error type by status and schema. Where **one status has several schemas** (`422` of `POST /orders`; `409` of `pay` and `ship`), the document uses `oneOf`, and the problem's **`type` URI** (`https://voltstore.com/errors/<kind>`) tells them apart at runtime. `detail`, `timestamp` and `service` are optional. |
| Status codes per operation | Every operation lists its success status and its error statuses. A generic `400 ValidationProblem` and `500 Problem` are added automatically; `400` also appears on operations whose only input is a path ID. | A `400` may come **without `errors`** (malformed JSON, a non-UUID path value, and Auth's framework errors with `type: about:blank`). Show field errors only when `errors` is present. |
| — | **Security as the gateway sees it (D7).** Public documents declare `bearerAuth` (JWT). Operations that need a token have `security: [{bearerAuth: []}]`, and their description starts with the role, e.g. `Role: CATALOG_MANAGER.` `GET /catalog/products` is `[{bearerAuth: []}, {}]` (token optional). Public reads have no security. The `X-User-*` headers never appear. | Roles are **description text only**, not machine-readable; keep the role logic in the app. Send `Authorization: Bearer <token>` as today. |
| — | **Paths are gateway paths** (`/catalog/products`), with one server `url: /`. | Keep the `/api` prefix in the app's API layer (the proxy strips it); don't take base URLs from the document. |
| — | **Generated schema names.** The paging envelope is generic, so springdoc names it per element type, e.g. `PageResponseProductSummaryResponse`. Nested records have explicit names, e.g. `CartItemsRequestItem`, `OrderDetailsItem`, `FulfillmentQueueEntry`. Operation IDs are the controller method names (`browseProducts`, `place`, `pay`, …). | Expect these names in the generated types; alias them in `src/api/<service>.ts` if the names read badly. |
| Payment and Notification optional | Out of scope (no routed endpoints); the API Gateway has none either. | Nothing to generate for them. |
| Optional snapshot test | Done: `OpenApiDocumentTest` per service, run in CI. | Generated types change only when a committed `public.json` changes, so a diff of those files is the API change to review. |

### Details worth knowing

- **Paging:** every list answers `{ content, page, size, totalElements }`, all four required (BL-05, done in the same PR). `page` is zero-based; `size` is the effective page size. There is no `totalPages`.
- **Limits:** for Catalog's query objects, `minimum`/`maximum` appear in the parameter schemas. For Inventory's and Order's `page`/`size`, the limits are in the description only.
- **Order:** `POST /orders/{id}/ship` can answer `503` with `Retry-After` (Delivery unavailable). `reservationExpiresAt` is required in both the placed-order and the details responses. `paidAt`, `ttn` and `deliveryStatus` in the details are optional.

## Requirements

- [x] Each listed service has an OpenAPI 3.1 document that is not reachable through the gateway (committed file; runtime endpoint only in Compose)
- [x] Every routed operation lists its success and error statuses; error bodies reference shared problem-details schemas
- [x] A short doc says where each document is: [`openapi-conventions.md`](../../../docs/design/services-requirements/openapi-conventions.md#where-to-find-the-documents)
- [x] Optional: a test per service fails when the document changes unexpectedly (snapshot)
- [ ] The frontend generates its types from the committed `public.json` files and drops the hand-written ones (frontend side; marks the item `Done`)

## Frontend impact

- Until the PR is merged: hand-written types in `src/api/<service>.ts`, each citing its spec section; live browser tests catch drift.
- After the merge, in one frontend change:
  1. Add `openapi-typescript` and a script (for example `npm run gen:api`) that generates one file per service from `../<service>-service/openapi/public.json` for Auth, Catalog, Customer, Order, Inventory and Delivery. Commit the generated files, so the build doesn't need the backend folders.
  2. Rebuild `src/api/<service>.ts` on top of the generated `paths`/`components` types, keeping the existing function signatures where possible, and drop the hand-written DTO types.
  3. Re-check the mock handlers against the generated types: they must answer only the required fields as non-null, and must answer problems with the named shapes.
  4. Handle `null` and missing optional fields alike, and branch on the problem `type` where `oneOf` applies.
  5. Mark BL-04 and BL-05 `Done` and update the index.

## Notes

- The paging envelope ([BL-05](BL-05-uniform-paging-envelope.md)) was settled first, so the generated types are uniform.
- The Markdown specs stay the design (D9). If a generated type disagrees with a service spec, that's a backend bug: add a backlog item rather than adapting the frontend silently.
