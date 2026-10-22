# OpenAPI Documents — Conventions

> Decision record for how the services describe their REST APIs as OpenAPI documents (frontend backlog item [BL-04](../../../frontend/docs/backlog/BL-04-openapi-contracts.md)). Pilot implementation: catalog-service ([task 26](../../tasks/catalog-service/26-openapi-document.md)); the other services copy its building blocks. Agreed 2026-10-21.

**In scope:** the services with gateway-routed endpoints: Auth, Catalog, Customer, Inventory, Order, Delivery. **Out of scope:** the API Gateway (it has no controllers), Payment and Notification (no routed endpoints).

## Decisions

1. **D1 — Code-first, generated in a test, committed.** Each service uses springdoc-openapi **3.1.1** (the newest release, built against Spring Boot 4.1), pinned in its `pom.xml`. A test (`OpenApiDocumentTest`, a `@SpringBootTest` on the service's existing Testcontainers configuration) builds the documents in-process through MockMvc and compares them with the committed files. A mismatch fails the test, so CI catches drift between the code and the committed document. Running the test with `-Dopenapi.update=true` rewrites the files instead of comparing. No running service, cluster or network port is needed to produce a document. *Why:* the frontend generates its types from files without starting the 20-container stack. The Maven plugin alternative boots the real application (PostgreSQL and Kafka). Static source parsers (smart-doc) leave the Spring ecosystem and lag behind Java 25.
2. **D2 — The runtime endpoint is off by default.** `springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` are `false` in `application.yml`. Compose turns both on for every in-scope service (`environment:` entries in `docker-compose.yml`, because `.env` files are not committed). Kubernetes leaves them off. The test turns `api-docs` on for itself. *Why:* running services expose nothing unless a developer wants Swagger UI, and the question of which port to serve on goes away.
3. **D3 — Files and format.** The documents are committed at `<service>/openapi/public.json` and, for services with internal endpoints (Catalog, Inventory, Delivery), `<service>/openapi/internal.json`. Format: OpenAPI 3.1 (`springdoc.api-docs.version: openapi_3_1`), pretty-printed, keys sorted (`springdoc.writer-with-default-pretty-printer: true`, `springdoc.writer-with-order-by-keys: true`). `info.title` is `<Service> API` (for example `Catalog API`), and `info.version` is the constant `1`. There is one fixed `servers` entry: `url: /`, description `Through the API Gateway`. *Why:* the files sit next to the code that produces them, so a controller change and its document change land in one commit. Sorted keys and a fixed server keep the snapshot stable on every machine; springdoc otherwise writes the request URL into `servers`.
4. **D4 — Public and internal groups.** Each service has a documentation-only annotation `@InternalApi` (method level) in its `openapi` package. It defines two `GroupedOpenApi` beans: `public` (methods without `@InternalApi`) and `internal` (methods with it), served at `/v3/api-docs/public` and `/v3/api-docs/internal`. A service without internal endpoints defines only `public`. Every controller method behind a `hasRole("SERVICE")` rule carries `@InternalApi`. *Why:* public and internal operations share paths (`GET /catalog/products/batch-get` next to `GET /catalog/products/{id}`, and `GET` vs `PUT` `/inventory/stock/{id}/packaging`), so only a per-method marker can separate them.
5. **D5 — Optional unless explicitly required.** A schema property is **required (always present, never `null`)** only when its field or record component is marked explicitly:
   - response DTOs and problem schemas: `@Schema(requiredMode = Schema.RequiredMode.REQUIRED)`;
   - request DTOs: a Bean Validation `@NotNull`, `@NotBlank` or `@NotEmpty` (springdoc reads these as required), or `@Schema(requiredMode = REQUIRED)` where the service checks the field by hand without a constraint annotation.

   A nested record gets an explicit `@Schema(name = …)` when springdoc would name it generically (`Item`, `Delivery`, `Entry`): springdoc uses the simple class name, and two nested records with the same name would overwrite each other in the document. Examples: Customer's `CartItemsRequestItem`, Order's `OrderDetailsItem` and `FulfillmentQueueItem`.

   Every unmarked property is optional. Clients treat a missing property and a `null` value the same way. Nullability is not encoded separately (no `type: [T, "null"]`). Each service task marks every property that can never be null. *Why:* every non-null guarantee is visible on the field itself, and springdoc supports this out of the box with no custom model converter. It costs more annotations, and a forgotten mark only makes the document weaker (a field shown as optional), never wrong.
6. **D6 — Error responses.** An `OperationCustomizer` adds two responses to operations that don't already declare them: `400` with `ValidationProblem` (only on operations that have parameters or a request body) and `500` with `Problem` (on every operation). Every other error status the operation can return is declared explicitly with `@ApiResponse` on the controller method: validation `400` where its EP card names field rules, and `401`, `403`, `404`, `409`, `422`, `402`, `424` and `503`. Each points to a named problem schema (next section) with media type `application/problem+json`; where one status can answer several schemas, the `@Schema` lists them with `oneOf`. The generic `400` also lands on operations whose only input is a path variable: a non-UUID value there may answer a problem without `errors`, which `ValidationProblem` allows. A `503` also declares the `Retry-After` header when the service sends it. *Why:* the operation-specific statuses stay visible next to the code, without dozens of copies of the generic ones.
7. **D7 — Security as a client sees it.** The documents describe the API as the API Gateway exposes it. Paths are the gateway paths, which the gateway forwards unchanged, and the trusted `X-User-*` headers never appear (they are read by a filter, not by controllers).
   - Public documents declare a `bearerAuth` scheme (HTTP bearer, JWT).
   - An operation the gateway routes as `REQUIRED` has `security: [{bearerAuth: []}]`, and its description starts with the required role, for example `Role: CATALOG_MANAGER.`
   - An `OPTIONAL` operation (only `GET /catalog/products`) has `security: [{bearerAuth: []}, {}]` and explains what the role changes. Springdoc drops an empty `@SecurityRequirement`, so the method carries the documentation-only `@OptionalAuthentication` (Catalog's `openapi` package), and an operation customizer of the public group sets the pair.
   - A `PUBLIC` operation has no security.
   - Internal documents declare an `internalToken` scheme (API key in the header `X-Internal-Token`) on every operation.
8. **D8 — Swagger UI is read-only.** When it is enabled (Compose), Swagger UI shows the documents but cannot send requests (`springdoc.swagger-ui.supported-submit-methods: []`). Manual calls keep going through the API test collections (`VoltStore_*API_Tests*.json`). *Why:* "Try it out" against the service would bypass the gateway by typing trusted headers by hand. Against the gateway it fails, because the gateway has no CORS.
9. **D9 — The Markdown specs remain the design.** `docs/design/` is the source of truth for intended behaviour. The OpenAPI documents describe what is implemented. When they disagree, one of them has a bug, the same as code vs. spec today. A task that changes an endpoint updates the committed document in the same commit (the snapshot test forces it).

## Problem schemas

Every service declares its problem schemas as documentation-only Java records in its `openapi` package and references them with `@Schema(implementation = …)`. They follow D5: the fields listed as required carry `@Schema(requiredMode = REQUIRED)`, and the rest are optional. Shared fields:

| Schema | Fields | Used for |
|---|---|---|
| `Problem` | `type`, `title`, `status` (required); `detail`, `instance`, `timestamp`, `service` (optional) | Every error without an extension (`401`, `403`, `404`, most `409`, `500`, `503`) |
| `ValidationProblem` | `Problem` fields + `errors` (optional, map of field name → list of messages) | `400`: field errors (`errors` present) or a malformed request (`errors` absent in some services) |

`timestamp` and `service` are optional because framework-built errors in Auth carry neither. Service-specific schemas extend the shared fields:

| Service | Schema | Extension fields | Status |
|---|---|---|---|
| Catalog | `ActivationRejectedProblem` | `reason` (string, e.g. `packaging_data_missing`, `stock_item_not_found`) | `422` on EP-CAT-03 |
| Customer | `CartRuleProblem` | `violation` (enum `TOO_MANY_PRODUCTS`, `QUANTITY_LIMIT_EXCEEDED`, `DUPLICATE_PRODUCT`); optional: `resultingProductCount`, `maxProducts`, `productIds`, `maxQuantity` | `422` on cart operations |
| Inventory | `InsufficientStockProblem` (internal) | `insufficientItems` (`[{productId, requested, available}]`) | `422` on EP-INV-06 |
| Inventory | `PackagingNotConfiguredProblem` (internal) | `reason` (`packaging_data_missing`) | `422` on EP-INV-04/05 |
| Order | `IneligibleItemsProblem` | `ineligibleItems` (`[{productId, reason}]`, reason `NOT_ACTIVE` \| `INSUFFICIENT_STOCK`) | `422` on EP-ORD-01/02 |
| Order | `InsufficientStockProblem` | `insufficientItems` (as Inventory's) | `422` on EP-ORD-02 |
| Order | `OrderTotalTooLargeProblem` | `maxTotal` (number) | `422` on EP-ORD-02 |
| Order | `OrderStateProblem` | `orderStatus` (order status enum) | `409` where an `InvalidOrderStateException` can occur |
| Order | `PaymentDeclinedProblem` | `reason` (string) | `402` on EP-ORD-03 |
| Delivery | `TtnGenerationFailedProblem` | `reason` (string) | `424` on EP-DEL-03 |

Auth uses only `Problem` and `ValidationProblem`. The extension fields mirror what each service's `GlobalExceptionHandler` writes today; if a handler changes, its schema changes with it.

## Where to find the documents

| Service | Committed files | Compose (stack running) |
|---|---|---|
| Auth | `auth-service/openapi/public.json` | `http://localhost:8086/swagger-ui.html` |
| Catalog | `catalog-service/openapi/public.json`, `internal.json` | `http://localhost:8081/swagger-ui.html` |
| Customer | `customer-service/openapi/public.json` | `http://localhost:8087/swagger-ui.html` |
| Inventory | `inventory-service/openapi/public.json`, `internal.json` | `http://localhost:8084/swagger-ui.html` |
| Order | `order-service/openapi/public.json` | `http://localhost:8082/swagger-ui.html` |
| Delivery | `delivery-service/openapi/public.json`, `internal.json` | `http://localhost:8083/swagger-ui.html` |

In Compose, the raw documents are at `/v3/api-docs/public` and `/v3/api-docs/internal` on the same ports. In Kubernetes the endpoints are off; use the committed files. The gateway never routes these paths (its route table is an explicit allowlist), and the services are not reachable from outside the cluster.

The frontend generates its types from the committed public files, for example `openapi-typescript ../catalog-service/openapi/public.json`.

## Building blocks per service

Copied per service, like the `security` package ([security model](security-model.md), decision 4). The pilot (Catalog) sets the exact shape, and the other services copy it.

- **Dependency:** `org.springdoc:springdoc-openapi-starter-webmvc-ui`.
- **`openapi` package:** `OpenApiConfig` (the `OpenAPI` bean with info and server; the `GroupedOpenApi` beans, each adding its own security scheme and the shared `Problem`/`ValidationProblem` schemas; the internal group also sets `internalToken` on every operation), `ErrorResponsesCustomizer` (the D6 `OperationCustomizer`, added to each group explicitly rather than as a global bean), `@InternalApi` (only in services with internal endpoints), and the problem schema records. Catalog alone has `@OptionalAuthentication` (D7).
- **`application.yml`:** the `springdoc.*` properties of D2, D3 and D8, plus `default-produces-media-type: application/json` (otherwise every success body is documented as `*/*`) and `override-with-generic-response: false` (otherwise springdoc infers responses from `GlobalExceptionHandler`, whose handlers carry no `@ResponseStatus`).
- **`SecurityConfig`:** `permitAll()` for `/v3/api-docs/**`, `/swagger-ui.html` and `/swagger-ui/**`, unconditionally. While springdoc is off, no handler exists and the paths answer `404`. The route-coverage test needs no change (it only walks the service's own `@RestController`s, and springdoc's endpoints are not mapped in its `@WebMvcTest` slice); `OpenApiDisabledByDefaultTest` checks the `404` with the full context instead.
- **`docker-compose.yml`:** `SPRINGDOC_API_DOCS_ENABLED: "true"` and `SPRINGDOC_SWAGGER_UI_ENABLED: "true"` under the service's `environment:`.
- **Controllers:** `@Tag` per controller (a readable group name in Swagger UI), `@Operation` with the EP number in the summary and the role first in the description (D7), `@SecurityRequirement(name = BEARER_AUTH)` on operations that need a token, `@ApiResponse` per D6, and `@ParameterObject` on query objects bound with `@ModelAttribute`.
- **`OpenApiDocumentTest`:** fetches each group, compares it as a JSON tree with the committed file (so line endings don't matter), and with `-Dopenapi.update=true` writes the response body to the file. A missing file fails the test unless the flag is set. Regenerate with `mvnw.cmd test -Dtest=OpenApiDocumentTest -Dopenapi.update=true`.

## Known trade-offs

- The documents describe the implementation, not the design (D9). A wrong implementation produces a wrong document, which only review against the spec catches.
- Optional fields (D5) become `field?: T` in the generated TypeScript, although the service may send `null`. Frontend code must treat `undefined` and `null` alike (for example `??`, not `=== undefined`).
- Required roles appear as description text, not as a machine-readable field: OpenAPI has no standard field for roles, and `SecurityConfig` stays the place where they are enforced.
- `@ApiResponse` annotations repeat the statuses that `GlobalExceptionHandler` and `SecurityConfig` decide. Nothing checks them automatically, so they can drift; the review of the committed document diff is the check.
