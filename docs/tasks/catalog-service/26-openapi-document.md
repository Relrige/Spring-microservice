---
status: DONE
service: catalog-service
---
# OpenAPI Document for Catalog (Pilot)

## Context
The REST contracts exist only as Markdown, so the frontend writes its TypeScript types by hand and nothing checks that the specs and the running services agree (frontend backlog BL-04). The team agreed (2026-10-21) that each service publishes an OpenAPI 3.1 document. A test generates it in-process and compares it with a committed file, so no running service or cluster is needed to produce it. The frontend generates its types from the committed files.

Catalog is the **pilot**: this task creates the building blocks (config, problem schemas, snapshot test, the D5 marking style) that the Auth, Customer, Inventory, Order and Delivery tasks then copy. Keep them small, self-contained in one `openapi` package, and commented, so copying is mechanical. Do [task 25](25-uniform-paging-envelope.md) first.
*References:* [OpenAPI Conventions (D1–D9, problem schemas, building blocks)](../../design/services-requirements/openapi-conventions.md), [Catalog Spec](../../design/services-specs/catalog-service-spec.md), [API Contracts](../../design/services-requirements/api-contracts.md), [Security Model](../../design/services-requirements/security-model.md), [Gateway route table](../../../api-gateway/src/main/resources/application.yml) (`authMode` per route)

## Acceptance Criteria
- [x] **Dependency and config:** `springdoc-openapi-starter-webmvc-ui` (the newest 3.x release that supports Spring Boot 4.1; record the version and why in *Implementation*). In `application.yml`: `api-docs` and `swagger-ui` disabled, OpenAPI 3.1, pretty printer, keys sorted, no submit methods in Swagger UI (D2, D3, D8).
- [x] **`openapi` package:** an `OpenApiConfig` with the `OpenAPI` bean (title `Catalog API`, version `1`, one server `/` "Through the API Gateway", `bearerAuth` and `internalToken` schemes), the `public` and `internal` `GroupedOpenApi` beans, and the D6 `OperationCustomizer`; plus `@InternalApi` and the problem schema records `Problem`, `ValidationProblem` and `ActivationRejectedProblem`.
- [x] **Groups (D4):** `batchGetProducts` carries `@InternalApi` and appears only in `internal.json`; every other operation appears only in `public.json`.
- [x] **Required fields (D5):** every response DTO field that is never null carries `@Schema(requiredMode = REQUIRED)` (for example all fields of `ProductDetailsResponse`, the paging envelope's `content`, `page`, `size` and `totalElements`). Every request DTO field that must be sent has a Bean Validation constraint or `@Schema(requiredMode = REQUIRED)`; the manually validated `ProductRequest` (update path) and `ProductStatusRequest` included. In the generated document, exactly these properties appear in `required`, and no property is typed `[T, "null"]`. Fields that can be null stay unmarked.
- [x] **Operations, security and errors (D6, D7):** every operation documents its success status and body, and the error statuses below in addition to the customizer's `400`/`500`. Problem bodies use `application/problem+json`.

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
- [x] **Query objects:** `ProductSearchQuery` and `CategorySearchQuery` appear as individual query parameters (`@ParameterObject`), not as one object parameter. `@AuthenticationPrincipal` parameters do not appear.
- [x] **Security config:** `/v3/api-docs/**`, `/swagger-ui.html` and `/swagger-ui/**` are `permitAll()`. The route-coverage and security tests are updated, and a test shows the paths answer `404` (not `401`/`403`) while springdoc is off.
- [x] **Snapshot test (D1):** `OpenApiDocumentTest` (`@SpringBootTest` with `springdoc.api-docs.enabled=true`, on the existing Testcontainers configuration) fetches `/v3/api-docs/public` and `/v3/api-docs/internal` through MockMvc and compares each, as a JSON tree, with `catalog-service/openapi/public.json` and `internal.json`. With `-Dopenapi.update=true` it writes the response bodies to those files instead and passes. A missing file or a difference fails, with a message naming the update command. Verify both directions by hand once: change a DTO field → the test fails; regenerate → it passes.
- [x] **Committed documents:** `openapi/public.json` and `openapi/internal.json` are generated and committed. Read them once as a client would: paths, parameters, required fields, the paging envelope `{ content, page, size, totalElements }`, error schemas, and security per operation.
- [ ] **Compose:** `catalog-service` gets `SPRINGDOC_API_DOCS_ENABLED: "true"` and `SPRINGDOC_SWAGGER_UI_ENABLED: "true"` under `environment:` in `docker-compose.yml`. With the stack up, `http://localhost:8081/swagger-ui.html` shows both groups, read-only. Kubernetes manifests are unchanged (springdoc stays off).
- [x] All existing Catalog tests still pass.

## Technical Notes / Constraints
- Settled decisions, not to be changed here: code-first with springdoc; documents generated by a test and committed under `<service>/openapi/`; runtime endpoint off by default and on only in Compose; groups by `@InternalApi`; optional unless explicitly marked required (D5); customizer for `400`/`500` plus explicit `@ApiResponse` for everything else; gateway-view security; read-only Swagger UI (D1–D9 in the conventions doc).
- **The building blocks are copied, not shared** (no common Maven module, as with the `security` package). Keep everything springdoc-specific in the `openapi` package except the annotations on controllers and DTOs, and comment each class for the next service that copies it.
- **D5 implementation:** no custom model converter or nullability rule; use springdoc's own `requiredMode` and its Bean Validation support. On Lombok `@Data` classes put `@Schema` on the field. On records, put it on the record component. Do not use JSpecify `@NonNull`/`@Nullable` or Bean Validation annotations on response DTOs to express this.
- **D6 implementation:** the `OperationCustomizer` adds `400 ValidationProblem` only when the operation has parameters or a request body, adds `500 Problem` everywhere, and never overwrites a response declared by `@ApiResponse`. Where one status can have several problem schemas (not the case in Catalog; it is in Order), use `oneOf` in the `@Schema`.
- **Determinism:** the committed file must be identical on every machine and every run: sorted keys, the fixed server `/`, and no timestamps or host names in examples. The comparison is by JSON tree, so CRLF/LF differences don't matter; the file is written exactly as the endpoint returns it.
- Do not change any endpoint's behaviour, path or status code in this task. If documenting reveals a mismatch between code and spec, record it under *Deviations* and raise it; don't fix it silently.
- The generic `PageResponse<T>` gets springdoc's generated names (for example `PageResponseProductSummaryResponse`); that is accepted.
- The frontend switch to generated types is frontend work (their BL-04) and is not part of this task.

## Decisions (2026-10-21)
- springdoc-openapi **3.1.1**: the newest release on Maven Central (2026-09), built against Spring Boot 4.1.0 (we run 4.1.1), with swagger-core 2.2.55. 3.0.x targets Boot 4.0. The other services pin the same version.
- Done in one run with the other springdoc tasks on the branch `springdoc`, one commit per service, one PR. `docs/design/` is not edited in this run (the final review says what needs a spec sync), and the branch is pushed only after that sync is approved.
- The customizer's generic `400 ValidationProblem` also lands on operations whose only input is a path variable (for example `DELETE /catalog/categories/{id}`). There a non-UUID `{id}` answers a `bad-request` problem without `errors`; that still matches `ValidationProblem`, whose `errors` is optional. Accepted rather than special-casing the customizer.
- Where the code and the task table differ, the code (EP cards and `GlobalExceptionHandler`) is documented, and the difference is recorded below. No behaviour was changed.
- The Compose check (Swagger UI on port 8081, read-only, both groups) is done once for all six services at the end of the run.

## Implementation Notes (2026-10-21)
- **Dependency and configuration.** `springdoc-openapi-starter-webmvc-ui` 3.1.1 in `pom.xml`. `application.yml` has a `springdoc` block: `api-docs` and `swagger-ui` off, `version: openapi_3_1`, pretty printer and sorted keys, `supported-submit-methods: []`, plus two properties explained under *Deviations*.
- **`openapi` package** (everything springdoc-specific, commented for the next service that copies it):
  - `OpenApiConfig`: the `OpenAPI` bean (title `Catalog API`, version `1`, one server `/` "Through the API Gateway") and the `public` and `internal` `GroupedOpenApi` beans. Each group selects methods by `@InternalApi`, adds its own security scheme (`bearerAuth` or `internalToken`), and adds `Problem` and `ValidationProblem` to its components (the customizer references them even where no annotation names them). The internal group sets `security: [{internalToken: []}]` on every operation, so internal methods carry no security annotation.
  - `ErrorResponsesCustomizer` (D6): `400 ValidationProblem` when the operation has parameters or a body, `500 Problem` everywhere, never overwriting a declared response. Added to each group explicitly, not as a global bean.
  - `@InternalApi`, `@OptionalAuthentication` (see *Deviations*), and the documentation-only records `Problem`, `ValidationProblem` and `ActivationRejectedProblem` (`reason` required: the handler always sets it, and the readiness client falls back to a fixed code).
- **Controllers.** Every operation has `@Operation` (summary with the EP number; description starting with `Role: CATALOG_MANAGER.` or `Public.`), `@SecurityRequirement(name = BEARER_AUTH)` where the gateway requires a token, a described success response, and the error responses of the task table with `application/problem+json`. The `503` of EP-CAT-03 declares the `Retry-After` header. `batchGetProducts` carries `@InternalApi`. The query objects are `@ParameterObject`, so they appear as separate query parameters; `page`, `size`, `sortBy` and `status` got parameter descriptions (defaults, format). Springdoc's security support leaves out `@AuthenticationPrincipal`.
- **Required fields (D5).** Every field of every response DTO is never null (all entity columns are `NOT NULL`), so all carry `@Schema(requiredMode = REQUIRED)`, including the paging envelope. Request DTOs needed no change: `ProductRequest`, `CategoryRequest` and `ProductStatusRequest` already carry `@NotBlank`/`@NotNull` even where the controller validates by hand (no `@Valid`), and springdoc reads them. The generated documents contain no `[T, "null"]` type.
- **Security.** `SecurityConfig` permits `/v3/api-docs/**`, `/swagger-ui.html` and `/swagger-ui/**`.
- **Snapshot test.** `OpenApiDocumentTest` (`@SpringBootTest(properties = "springdoc.api-docs.enabled=true")` on `TestcontainersConfiguration`) fetches both groups through MockMvc and compares them as `JsonNode` trees with `openapi/public.json` and `openapi/internal.json`. With `-Dopenapi.update=true` (Surefire passes it on as a system property) it writes the bodies instead. Failure messages name the regenerate command.
- **Compose.** `catalog-service` got `SPRINGDOC_API_DOCS_ENABLED` and `SPRINGDOC_SWAGGER_UI_ENABLED` under `environment:`. Kubernetes is unchanged.
- **Verified:**
  - `OpenApiDocumentTest` in both directions by hand: removing the `REQUIRED` mark from `CategoryResponse.name` failed the public comparison with "openapi\public.json differs from the code. Regenerate with: ..."; restoring it passed. Moving `internal.json` away failed with "internal.json is missing. ...".
  - `OpenApiDisabledByDefaultTest`: with the default configuration, `/v3/api-docs`, `/v3/api-docs/public`, `/v3/api-docs/internal`, `/swagger-ui.html` and `/swagger-ui/index.html` answer `404` to an anonymous caller (permitted, no handler).
  - Read the committed documents as a client: 9 public operations and 1 internal, the envelope `{ content, page, size, totalElements }` all required, `security` per operation as in the table (`[{bearerAuth: []}, {}]` on `GET /catalog/products`, none on the three public reads), named problem schemas on every error.
  - Full suite `mvnw.cmd clean test`: 27 test classes, 267 tests, all green.

## Deviations from spec / task
- **`@OptionalAuthentication` marker.** D7 asks for `security: [{bearerAuth: []}, {}]` on the optional operation. Springdoc drops an `@SecurityRequirement(name = "")`, so the second, empty alternative cannot be written with standard annotations. A documentation-only annotation in the `openapi` package marks the method, and an operation customizer in the public group sets the pair. Alternatives: an `@Operation(extensions = ...)` hack (leaks `x-` fields into the document), or matching the path in the customizer (hides the rule far from the controller). Only Catalog needs it; the other services do not copy it.
- **Two extra springdoc properties.** `default-produces-media-type: application/json`: without it springdoc documents every success body as `*/*`, which generators type poorly. `override-with-generic-response: false`: otherwise springdoc infers responses from `GlobalExceptionHandler`, whose handlers carry no `@ResponseStatus`, and would add generic entries to every operation. D6 already says where error responses come from.
- **`@Tag` per controller** (`Products`, `Categories`) instead of the generated `product-controller` / `category-controller`. A detail for readers of Swagger UI; operation IDs (the method names) are unchanged.
- **Route-coverage test unchanged.** `RouteSecurityRulesTest` only walks `@RestController` handlers in `ua.edu.ukma.*`, and springdoc's endpoints are not mapped in the `@WebMvcTest` slice, so it needs no exception for the new rule. `OpenApiDisabledByDefaultTest` covers the rule instead (full context, springdoc off, `404`).
- **`400` on path-only operations** (`GET /catalog/products/{id}`, `DELETE /catalog/categories/{id}`): the customizer adds it although the table lists none for them; see *Decisions*.
- **Code vs. task table:** no difference found. Malformed JSON and non-UUID path values answer a `bad-request` problem without `errors`, which the optional `errors` of `ValidationProblem` covers.
