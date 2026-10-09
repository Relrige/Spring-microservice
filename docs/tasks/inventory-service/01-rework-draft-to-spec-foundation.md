---
status: TODO
service: inventory-service
---
# Rework Draft Module into the Spec Foundation

## Context
`inventory-service` contains draft code written before the spec. For this service the spec is the source of truth, so the draft is reshaped rather than extended. A review of the draft against the [Inventory Service Spec](../../design/services-specs/inventory-service-spec.md) found these differences:

| Area | Draft | Spec / target |
|---|---|---|
| Route prefix | `/api/v1/inventory/...` | `/inventory/...` (the gateway forwards paths unchanged) |
| Stock identity | `StockItem` has its own `id` and a `sku` string | `StockItem` is identified by `productId` (the Catalog `Product.id`); there is no SKU |
| Packaging | `weightGrams` (Integer) and one free-text `dimensions` string | four nullable integers: `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm` |
| Reservations | one row per order and a single SKU; statuses `ACTIVE, COMPLETED, CANCELLED`; has `expiresAt` | `StockItemReservation` (`id`, `orderId`, `productId`, `quantity`, `status`), one row per product of an order (1+ rows per order); statuses `ACTIVE, RELEASED, CONSUMED`; **no** `expiresAt` (Order Service owns the TTL) |
| Idempotency | `IdempotencyRecord` table and an `Idempotency-Key` header | idempotency by `orderId`: a second reserve for an order with `ACTIVE` reservations is `409` |
| Endpoints | `GET/POST /stock`, `GET /stock/{sku}`, `PATCH /stock/{sku}/details`, `POST /reserve`, `/confirm-reservation/{orderId}`, `/cancel-reservation/{orderId}` | EP-INV-01…07 (paths and bodies in the spec) |
| Request style | `@RequestParam` values, single item per reserve | JSON request bodies; batch reserve of many items |
| API model | JPA entities returned directly | separate request/response DTOs |
| Errors | `RuntimeException`s (→ `500`) for missing items and insufficient stock | typed exceptions mapped to `400`/`404`/`409`/`422` |
| Events | none | emits `ProductStockReplenished` / `ProductStockDepleted`, consumes `ProductCreated` / `OrderShipped` |
| Schema | `ddl-auto: update`, Flyway starter present but no migrations | Flyway owns the schema, `ddl-auto: validate` |
| Tests | context-load test on `postgres:latest` | Testcontainers on the version used in deployment (`postgres:18-alpine`) |

This task prepares the module so the following tasks add features on a clean base. It adds no endpoint.

*References:* [Inventory Service Spec](../../design/services-specs/inventory-service-spec.md), [Domain Entities — Inventory](../../design/entities/entities.md), [Catalog Service foundation task](../catalog-service/01-rework-draft-to-spec-foundation.md)

## Acceptance Criteria
- [ ] Remove draft code that contradicts the spec: `InventoryController`, `InventoryService`, `IdempotencyRecord` (+ repository), `StockReservation` (replaced in [task 12](12-stock-reservation-entity-and-persistence.md)), `StockItem` as drafted (replaced in [task 02](02-stock-item-entity-and-persistence.md)), `InsufficientStockException` as drafted, and the draft repositories.
- [ ] Align the package layout with `auth-service` (`controllers`, `services`, `repositories`, `entities`, `dto`, `exceptions`, `config`, `security`). Rename `domain.entity` → `entities`.
- [ ] Update `pom.xml`: add `spring-boot-starter-validation` and matching test starters (`validation-test`, `actuator-test`) as in `auth-service`. Remove `spring-boot-devtools` if the other services do not use it. Spring Security is added in [task 07](07-trusted-header-security.md).
- [ ] `application.yml`: `ddl-auto: validate`; keep the actuator probes.
- [ ] Test `TestcontainersConfiguration` uses `postgres:18-alpine` (the version in `docker-compose.yml` and `k8s/`), not `postgres:latest`.
- [ ] A context-load smoke test passes against a real PostgreSQL (no entities yet, so `validate` has nothing to check).
- [ ] `.env`, `.env.example` and `k8s/inventory/app-config.yaml` stay consistent (database settings only; Kafka settings come in [task 05](05-kafka-spring-setup-and-topic.md), `INTERNAL_TOKEN` in [task 07](07-trusted-header-security.md)).

## Technical Notes / Constraints
- Deleting the draft is intended; nothing outside this module should depend on the draft `/api/v1/...` endpoints. Check `order-service` for any copy of the old inventory contract (`/reserve`, `/confirm-reservation`, `/cancel-reservation`, `Idempotency-Key`) and note what must be aligned later.
- Avoid Lombok `@Data` on JPA entities; decide the entity convention once here (shared with the Catalog service convention, see its task 01) and reuse it in tasks 02 and 12.
- `target/` and `.idea/` are build artefacts; do not edit them by hand.
- Compare the new `pom.xml` with `auth-service/pom.xml` instead of inventing a different dependency set.
