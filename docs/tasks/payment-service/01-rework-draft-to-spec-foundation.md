---
status: DONE
service: payment-service
---
# Rework Draft Module into the Spec Foundation

## Context
`payment-service` contains draft code written before the spec. As with Catalog and Inventory, the spec is the source of truth, so the draft is reshaped rather than extended. A review of the draft against the [Payment Service Spec](../../design/services-specs/payment-service-spec.md) found these differences:

| Area | Draft | Spec / target |
|---|---|---|
| Route prefix | `/api/v1/payments/...` | `/payments/charge`, `/payments/refund` (internal; never routed by the gateway) |
| Payment flow | two steps: `initiate` creates a `PENDING` row, an external `callback` completes it (redirect/webhook style) | one synchronous step: Payment Service calls the gateway through its own Anti-Corruption Layer and answers Order Service with the result |
| Transaction model | one row per order; the refund **changes** that row to `REFUNDED` | append-only history: a `CHARGE` row and, later, a separate `REFUND` row; `type { CHARGE, REFUND }`, `status { SUCCESS, FAILED }` |
| Gateway reference | `externalTransactionId` (unique) | `gatewayTransactionId` (nullable; `null` when the gateway rejected or did not answer) |
| Lookup | `findByOrderId` returns `Optional` (breaks as soon as an order has 2+ rows) | queries by `orderId` + `type` + `status` |
| Request style | `@RequestParam` values | JSON request bodies with validation |
| API model | JPA entity returned directly | separate request/response DTOs |
| Errors | `orElseThrow()` → `500` | typed exceptions mapped to `400`/`402`/`409`/`422`/`502`/`504` |
| Schema | `ddl-auto: update`, Flyway starter present but no migrations | Flyway owns the schema, `ddl-auto: validate` |
| Security | none | internal-only routes (`hasRole("SERVICE")`), [task 04](04-trusted-header-security.md) |

This task prepares the module so the following tasks add features on a clean base. It adds no endpoint.

*References:* [Payment Service Spec](../../design/services-specs/payment-service-spec.md), [Domain Entities — Payment](../../design/entities/entities.md), [Inventory foundation task](../inventory-service/01-rework-draft-to-spec-foundation.md)

## Acceptance Criteria
- [x] Remove draft code that contradicts the spec: `PaymentController`, `PaymentService`, `PaymentTransactionRepository` and `PaymentTransaction` as drafted (replaced in [task 02](02-payment-transaction-entity-and-persistence.md)).
- [x] Align the package layout with the other reworked services (`controllers`, `services`, `repositories`, `entities`, `dto`, `exceptions`, `config`, `security`, plus `gateway` for the ACL from [task 05](05-payment-gateway-acl-and-mock.md)). Rename `domain.entity` → `entities`.
- [x] Update `pom.xml`: add `spring-boot-starter-validation` and the matching test starters as in `inventory-service`. Remove `spring-boot-devtools` if the other services no longer use it. Spring Security is added in [task 04](04-trusted-header-security.md).
- [x] `application.yml`: `ddl-auto: validate`; keep the actuator probes and `problem-details`.
- [x] Test `TestcontainersConfiguration` uses `postgres:18-alpine` (the version in `docker-compose.yml` and `k8s/`).
- [x] A context-load smoke test passes against a real PostgreSQL.
- [x] `.env`, `.env.example` and `k8s/payment/app-config.yaml` stay consistent (database settings only; `INTERNAL_TOKEN` comes in [task 04](04-trusted-header-security.md)).

## Technical Notes / Constraints
- Check `order-service` for a copy of the old contract (`/api/v1/payments/initiate`, `/callback`, `/refund/{orderId}`) and note what must be aligned later in the Order payment client.
- Avoid Lombok `@Data` on JPA entities; reuse the entity convention chosen for Catalog and Inventory.
- `target/` and `.idea/` are build artefacts; do not edit them by hand.

## Decisions
Agreed on 2026-10-15:
- **Payment flow: follow the spec.** Synchronous server-side charge with `cardDetails` in the request, not the draft's redirect + callback flow. The draft's `initiate` / `callback` endpoints and the `PENDING` status are removed. Consequence: card data passes through Order Service and Payment Service, so [task 08](08-card-data-protection.md) is required, not optional.

## Deviations from task
- `spring-boot-devtools` is **kept**: Inventory, Order and Delivery still use it, so removing it here would make Payment the odd one out. Remove it from all services together if the team decides to.
- The package layout (`controllers`, `services`, `entities`, ...) has no folders yet: Java packages exist only once they contain a class, so each package appears with the first task that adds one.

## Implementation
Done on 2026-10-15:
- Deleted the draft `PaymentController`, `PaymentService`, `PaymentTransactionRepository` and `domain.entity.PaymentTransaction`; only `PaymentServiceApplication` is left in `main`.
- No other service depended on the draft contract: `order-service` has no Payment client yet (it only has a `PENDING_PAYMENT` status). The Order payment client must use `POST /payments/charge` and `POST /payments/refund` with JSON bodies, the internal token, and the `409` meaning "already paid / already refunded" ([task 06](06-charge-card.md), [task 07](07-refund-order.md)).
- `pom.xml`: added `spring-boot-starter-validation`, `spring-boot-starter-validation-test` and `spring-boot-starter-actuator-test`.
- `application.yml`: `ddl-auto: validate` (with a comment), actuator probes and `problem-details` kept.
- `TestcontainersConfiguration` now uses `postgres:18-alpine`.
- `.env`, `.env.example`, `k8s/payment/app-config.yaml` and `secret-template.yaml` already held the same database settings only; unchanged.
- Verified: `./mvnw test` passes 1 test (`contextLoads`) against PostgreSQL 18.6 in Testcontainers.
