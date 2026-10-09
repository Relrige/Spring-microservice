---
status: TODO
service: catalog-service
---
# Rework Draft Module into the Spec Foundation

## Context
`catalog-service` already contains draft code written before the spec. For this service the spec is the source of truth, so the draft is reshaped rather than extended. A review of the draft against the [Catalog Service Spec](../../design/services-specs/catalog-service-spec.md) found these differences:

| Area | Draft | Spec / target |
|---|---|---|
| Route prefix | `/api/v1/catalog/products` | `/catalog/...` (the gateway forwards paths unchanged) |
| Product fields | `sku`, `category` (String), `price`, `specs`, `deleted` | `title`, `description`, `categoryId`, `basePrice`, `status`, `stockStatus`; no `sku`, no `specs` |
| Categories | none (plain string on the product) | separate `Category` entity with unique `name` |
| Removal | `DELETE /products/{id}` sets `deleted = true` | hard deletes are forbidden; `status = NOT_ACTIVE` is the only removal mechanism |
| Endpoints | create, list, get, update, delete, `/batch` | EP-CAT-01…09 plus the event consumers; `/batch` becomes `/batch-get` |
| API model | the JPA entity is the request and response body | separate request/response DTOs |
| Schema | `ddl-auto: update`, Flyway starter present but no migrations | Flyway owns the schema, `ddl-auto: validate` |
| Validation | hand-written checks inside the service | Bean Validation on DTOs |
| Security | none | trusted-header Spring Security (see [task 12](12-trusted-header-security.md)); the first CRUD and Kafka tasks are built before it and run locally without authentication |
| Tests | context-load test on `postgres:latest` | Testcontainers on the Postgres version used in deployment (`postgres:18-alpine`) |
| Exceptions | `DuplicateSkuException`, `InvalidProductDataException` | replaced by spec-driven exceptions (see [task 04](04-error-handling-and-validation.md)) |

This task prepares the module so the following tasks can add features on a clean base. It does not add any endpoint.

*References:* [Catalog Service Spec](../../design/services-specs/catalog-service-spec.md), [Domain Entities — Catalog](../../design/entities/entities.md), [Auth Service scaffold task](../auth-service/01-scaffold-auth-service.md)

## Acceptance Criteria
- [ ] Remove draft code that contradicts the spec: `Product` entity fields (`sku`, `specs`, `deleted`, string `category`), `ProductController`, `ProductService`, `ProductRepository`, `DuplicateSkuException`, `InvalidProductDataException`. Keep `CatalogServiceApplication` and the exception-advice skeleton only if they are reused unchanged.
- [ ] Align the package layout with `auth-service` (`controllers`, `services`, `repositories`, `entities`, `dto`, `exceptions`, `config`, `security`) so the services look alike. Rename `domain.entity` → `entities` and `exception` → `exceptions`.
- [ ] Update `pom.xml`: add `spring-boot-starter-validation` and the matching test starters (`validation-test`, `actuator-test`) as in `auth-service`. Remove `spring-boot-devtools` if it is not used by the other services. Spring Security is added later in [task 12](12-trusted-header-security.md).
- [ ] `application.yml`: `ddl-auto: validate`; keep the actuator probes.
- [ ] Test `TestcontainersConfiguration` uses `postgres:18-alpine` (the version in `docker-compose.yml` and `k8s/`), not `postgres:latest`.
- [ ] A context-load smoke test passes against a real PostgreSQL. With no migrations and `validate`, this still passes because no entities exist yet.
- [ ] `.env`, `.env.example` and `k8s/catalog/app-config.yaml` stay consistent with each other (database settings only; `INTERNAL_TOKEN` comes in [task 12](12-trusted-header-security.md), Kafka settings in [task 10](10-kafka-spring-setup-and-topic.md)).

## Technical Notes / Constraints
- Deleting draft code is intended: the spec requires a rebuild, and nothing outside this module depends on the draft `/api/v1/...` endpoints. Check `order-service` for any copy of the old catalog contract (`/products/batch`) before deleting and note what must be aligned later (see [task 17](17-batch-get-products-internal.md)).
- Avoid Lombok `@Data` on JPA entities (generated `equals`/`hashCode`/`toString` interact badly with lazy loading and generated IDs). Decide the entity convention once here, record it, and reuse it in tasks 02 and 03.
- `target/` and `.idea/` files are build artefacts; do not edit them by hand.
- Compare the new `pom.xml` with `auth-service/pom.xml` instead of inventing a different dependency set.
