---
status: DONE
service: order-service
---
# Rework Draft Module into the Spec Foundation

## Context
`order-service` contains draft code written before the spec, plus a tested Catalog client ([Declarative HTTP Client](declarative-http-client.md), done). The [Order spec](../../design/services-specs/order-service-spec.md) was rewritten on 2026-10-16 (registered customers only, aligned with the implemented services), so the draft is reshaped rather than extended. A review of the draft against the spec:

| Area | Draft | Spec / target |
|---|---|---|
| Route prefix | `/api/v1/orders/...` | `/orders/...` (the gateway forwards paths unchanged) |
| Who can order | no security at all | registered customers only (`CUSTOMER`); workers for the queue and dispatch |
| Statuses | `PENDING_PAYMENT, PAID, PAYMENT_FAILED, CANCELLED, SHIPPED` | the 8 states of the [state machine](../../design/entities/order-state-machine.md); no `PAYMENT_FAILED` (a declined card leaves the order `PENDING_PAYMENT`) |
| Endpoints | create from a raw `Order` body, `GET /customer/{customerId}`, `PATCH /{id}/status` | EP-ORD-01…08; the customer comes from `X-User-Id`, never from the path; status changes only through the business endpoints |
| API model | JPA entity accepted and returned directly | separate request/response DTOs |
| Delivery details | `recipientName, phone, city, warehouseAddress` | `name, phone, address, carrier` (what Delivery's `OrderPaid` consumer reads) |
| Catalog client | `GET /products/batch`, `ProductDto { id, price, activenessStatus, stockStatus }`; the fallback returns an empty list | `GET /catalog/products/batch-get`, `{ productId, title, basePrice, status, stockStatus }`; unavailability must surface as `503`, never as "no products" |
| Errors | `orElseThrow()` / `IllegalStateException` → `500` | typed exceptions mapped per the spec ([task 03](03-error-handling-and-validation.md)) |
| Schema | `ddl-auto: update`, no migrations | Flyway owns the schema, `ddl-auto: validate` |

This task prepares the module so the following tasks add features on a clean base. It adds no endpoint.
*References:* [Order Spec](../../design/services-specs/order-service-spec.md), [Inventory foundation task](../inventory-service/01-rework-draft-to-spec-foundation.md), [Payment foundation task](../payment-service/01-rework-draft-to-spec-foundation.md)

## Acceptance Criteria
- [x] Remove draft code that contradicts the spec: `OrderController`, `OrderService`, `Order`, `DeliverySnapshot` and `OrderItemSnapshot` as drafted (replaced in [task 02](02-order-entity-and-persistence.md)), `ValidateCheckoutRequest`, `CheckoutNotValidException` and `InventoryUnavailableException` as drafted.
- [x] Keep the Catalog client, its configuration and its WireMock tests; they are reworked in [task 05](05-internal-clients.md), not here.
- [x] Align the package layout with the reworked services (`controllers`, `services`, `repositories`, `entities`, `dto`, `exceptions`, `config`, `security`, `clients`, `events`, `outbox`, `jobs`). Rename `domain.entity` → `entities`; move `utils.constants` into the package that uses it.
- [x] `pom.xml`: replace `spring-boot-starter-web` with `spring-boot-starter-webmvc` (as in the other Boot 4 services); add the test starters used by Inventory (`webmvc-test`, `data-jpa-test`, `flyway-test`, `validation-test`, `actuator-test`). Security and Kafka come in tasks 04 and 06.
- [x] `application.yml`: `ddl-auto: validate`; actuator probes and `problem-details` kept; the Resilience4j settings stay until task 05 decides on them.
- [x] `TestcontainersConfiguration` uses `postgres:18-alpine`; a context-load smoke test passes.
- [x] `.env`, `.env.example` and `k8s/order/app-config.yaml` stay consistent (database settings only so far).

## Technical Notes / Constraints
- Avoid Lombok `@Data` on JPA entities (the convention of the other services).
- Nothing outside this module calls the draft `/api/v1/orders` endpoints; the gateway routes for Order are still deferred ([gateway task 10](../api-getaway/10-remaining-service-routes.md)).
- The older task [internal-token-sender-and-security](internal-token-sender-and-security.md) is split into [task 04](04-trusted-header-security.md) (receiver side) and [task 05](05-internal-clients.md) (sender side).

## Implementation
Done on 2026-10-16:
- Deleted the draft `OrderController`, `GlobalExceptionHandler` (rewritten in task 03), `OrderService`, `domain.entity.*`, `dto.request.ValidateCheckoutRequest`, `CheckoutNotValidException`, `InventoryUnavailableException` and `OrderRepository` (rewritten in task 02). `utils.constants.CorrelationIdKeys` moved to `filters`, as in Inventory.
- Kept the Catalog client (`CatalogClient`, `ResilientCatalogClient`, `ClientsConfiguration`, `CatalogClientProperties`, `clients.dto.*`) and its tests for task 05.
- `pom.xml`: `spring-boot-starter-web` → `spring-boot-starter-webmvc`; added `webmvc-test`, `data-jpa-test`, `flyway-test`, `validation-test`, `actuator-test`.
- `application.yml`: `ddl-auto: validate`; the Resilience4j block is unchanged.
- `TestcontainersConfiguration`: `postgres:18-alpine`, now `public` so tests in sub-packages can import it.
- `.env`, `.env.example` and `k8s/order/app-config.yaml` already held database settings only; unchanged.

## Deviations from task
- **`spring-boot-starter-aop` 4.0.0-M2 replaced by `spring-boot-starter-aspectj`.** The draft pinned a pre-release (milestone) of Boot 4 inside a 4.1.1 build; Boot 4 renamed the starter, and the new one takes its version from the parent. Resilience4j's annotations still work (its 6 WireMock tests pass).
- **`CatalogClientWireMockTest` now uses a random port** (a `WireMockExtension` with `dynamicPort()` and `@DynamicPropertySource`, like `ResilientCatalogClientWireMockTest`). It was bound to port 8081 and failed whenever another program listened there, which happened on the development machine. The test content is unchanged; its stubs still describe the draft's `/products/batch` contract, which task 05 replaces.
- `spring-boot-devtools` kept, as in Inventory and Payment.
- Baseline before the task: 8 tests, 2 failing for environment reasons (Docker not running, port 8081 taken). After: 9 tests, all passing.
