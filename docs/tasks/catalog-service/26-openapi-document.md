---
status: TODO
service: catalog-service
---
# OpenAPI Document for Catalog (Pilot)

## Context
The REST contracts exist only as Markdown, so the frontend writes its TypeScript types by hand and nothing checks that the specs and the running services agree (frontend backlog BL-04). The team agreed (2026-10-21) that each service publishes an OpenAPI 3.1 document. A test generates it in-process and compares it with a committed file, so no running service or cluster is needed to produce it. The frontend generates its types from the committed files.

Catalog is the **pilot**: this task creates the building blocks (config, problem schemas, snapshot test, the D5 marking style) that the Auth, Customer, Inventory, Order and Delivery tasks then copy. Keep them small, self-contained in one `openapi` package, and commented, so copying is mechanical. Do [task 25](25-uniform-paging-envelope.md) first.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Catalog Spec](../../design/services-specs/catalog-service-spec.md), [API Contracts](../../design/services-requirements/api-contracts.md), [Security Model](../../design/services-requirements/security-model.md), [Gateway route table](../../../api-gateway/src/main/resources/application.yml) (`authMode` per route)

## Acceptance Criteria
- [ ] **Dependency and config:** `springdoc-openapi-starter-webmvc-ui` (the newest 3.x release that supports Spring Boot 4.1; record the version and why in *Implementation*). In `application.yml`: `api-docs` and `swagger-ui` disabled, OpenAPI 3.1, pretty printer, keys sorted, no submit methods in Swagger UI (D2, D3, D8).
- [ ] **`openapi` package:** an `OpenApiConfig` with the `OpenAPI` bean (title `Catalog API`, version `1`, one server `/` "Through the API Gateway", `bearerAuth` and `internalToken` schemes), the `public` and `internal` `GroupedOpenApi` beans, and the D6 `OperationCustomizer`; plus `@InternalApi` and the problem schema records `Problem`, `ValidationProblem` and `ActivationRejectedProblem`.
- [ ] **Groups (D4):** `batchGetProducts` carries `@InternalApi` and appears only in `internal.json`; every other operation appears only in `public.json`.
- [ ] **Required fields (D5):** every response DTO field that is never null carries `@Schema(requiredMode = REQUIRED)` (for example all fields of `ProductDetailsResponse`, the paging envelope's `content`, `page`, `size` and `totalElements`). Every request DTO field that must be sent has a Bean Validation constraint or `@Schema(requiredMode = REQUIRED)`; the manually validated `ProductRequest` (update path) and `ProductStatusRequest` included. In the generated document, exactly these properties appear in `required`, and no property is typed `[T, "null"]`. Fields that can be null stay unmarked.
- [ ] **Operations, security and errors (D6, D7):** every operation documents its success status and body, and the error statuses below in addition to the customizer's `400`/`500`. Problem bodies use `application/problem+json`.

  | Operation | Gateway auth | Explicit error responses |
  |---|---|---|
  | `GET /catalog/products` (EP-CAT-04) | OPTIONAL (`[{bearerAuth: []}, {}]`; the description says what `CATALOG_MANAGER` changes) | `400 ValidationProblem` |
  | `GET /catalog/products/{id}` (EP-CAT-05) | PUBLIC | `404 Problem` |
  | `POST /catalog/products` (EP-CAT-01) | `CATALOG_MANAGER` | `400 ValidationProblem`, `401`, `403`, `422 Problem` (category not found) |
  | `PUT /catalog/products/{id}` (EP-CAT-02) | `CATALOG_MANAGER` | `400 ValidationProblem`, `401`, `403`, `404`, `422 Problem` |
  | `PATCH /catalog/products/{id}/status` (EP-CAT-03) | `CATALOG_MANAGER` | `400 ValidationProblem`, `401`, `403`, `404`, `422 ActivationRejectedProblem`, `503 Problem` with header `Retry-After` |
  | `GET /catalog/categories` (EP-CAT-10) | PUBLIC | `400 ValidationProblem` |
  | `POST /catalog/categories` (EP-CAT-06) | `CATALOG_MANAGER` | `400 ValidationProblem`, `401`, `403`, `409 Problem` |
  | `PUT /catalog/categories/{id}` (EP-CAT-07) | `CATALOG_MANAGER` | `400 ValidationProblem`, `401`, `403`, `404`, `409` |
  | `DELETE /catalog/categories/{id}` (EP-CAT-08) | `CATALOG_MANAGER` | `401`, `403`, `404`, `409` |
  | `GET /catalog/products/batch-get` (EP-CAT-09, internal) | `internalToken` | `400 ValidationProblem`, `401` |

  `401`/`403`/`404`/`409` without a schema name use `Problem`. The EP cards' edge cases win over this table where they differ; record any difference under *Deviations*.
- [ ] **Query objects:** `ProductSearchQuery` and `CategorySearchQuery` appear as individual query parameters (`@ParameterObject`), not as one object parameter. `@AuthenticationPrincipal` parameters do not appear.
- [ ] **Security config:** `/v3/api-docs/**`, `/swagger-ui.html` and `/swagger-ui/**` are `permitAll()`. The route-coverage and security tests are updated, and a test shows the paths answer `404` (not `401`/`403`) while springdoc is off.
- [ ] **Snapshot test (D1):** `OpenApiDocumentTest` (`@SpringBootTest` with `springdoc.api-docs.enabled=true`, on the existing Testcontainers configuration) fetches `/v3/api-docs/public` and `/v3/api-docs/internal` through MockMvc and compares each, as a JSON tree, with `catalog-service/openapi/public.json` and `internal.json`. With `-Dopenapi.update=true` it writes the response bodies to those files instead and passes. A missing file or a difference fails, with a message naming the update command. Verify both directions by hand once: change a DTO field → the test fails; regenerate → it passes.
- [ ] **Committed documents:** `openapi/public.json` and `openapi/internal.json` are generated and committed. Read them once as a client would: paths, parameters, required fields, the paging envelope `{ content, page, size, totalElements }`, error schemas, and security per operation.
- [ ] **Compose:** `catalog-service` gets `SPRINGDOC_API_DOCS_ENABLED: "true"` and `SPRINGDOC_SWAGGER_UI_ENABLED: "true"` under `environment:` in `docker-compose.yml`. With the stack up, `http://localhost:8081/swagger-ui.html` shows both groups, read-only. Kubernetes manifests are unchanged (springdoc stays off).
- [ ] All existing Catalog tests still pass.

## Technical Notes / Constraints
- Settled decisions, not to be changed here: code-first with springdoc; documents generated by a test and committed under `<service>/openapi/`; runtime endpoint off by default and on only in Compose; groups by `@InternalApi`; optional unless explicitly marked required (D5); customizer for `400`/`500` plus explicit `@ApiResponse` for everything else; gateway-view security; read-only Swagger UI (D1–D9 in the conventions doc).
- **The building blocks are copied, not shared** (no common Maven module, as with the `security` package). Keep everything springdoc-specific in the `openapi` package except the annotations on controllers and DTOs, and comment each class for the next service that copies it.
- **D5 implementation:** no custom model converter or nullability rule; use springdoc's own `requiredMode` and its Bean Validation support. On Lombok `@Data` classes put `@Schema` on the field. On records, put it on the record component. Do not use JSpecify `@NonNull`/`@Nullable` or Bean Validation annotations on response DTOs to express this.
- **D6 implementation:** the `OperationCustomizer` adds `400 ValidationProblem` only when the operation has parameters or a request body, adds `500 Problem` everywhere, and never overwrites a response declared by `@ApiResponse`. Where one status can have several problem schemas (not the case in Catalog; it is in Order), use `oneOf` in the `@Schema`.
- **Determinism:** the committed file must be identical on every machine and every run: sorted keys, the fixed server `/`, and no timestamps or host names in examples. The comparison is by JSON tree, so CRLF/LF differences don't matter; the file is written exactly as the endpoint returns it.
- Do not change any endpoint's behaviour, path or status code in this task. If documenting reveals a mismatch between code and spec, record it under *Deviations* and raise it; don't fix it silently.
- The generic `PageResponse<T>` gets springdoc's generated names (for example `PageResponseProductSummaryResponse`); that is accepted.
- The frontend switch to generated types is frontend work (their BL-04) and is not part of this task.
