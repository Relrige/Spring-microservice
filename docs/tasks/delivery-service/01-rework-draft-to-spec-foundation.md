---
status: TODO
service: delivery-service
---
# Rework Draft Module into the Spec Foundation

## Context
`delivery-service` still contains the scaffold draft written before the spec. As with Catalog and Inventory, the spec is the source of truth, so the draft is reshaped rather than extended. A review of the draft against the [Delivery Service Spec](../../design/services-specs/delivery-service-spec.md) found these differences:

| Area | Draft | Spec / target |
|---|---|---|
| Route prefix | `/api/v1/delivery/...` | `/delivery/...` (the gateway forwards paths unchanged) |
| Aggregate | `Consignment` with embedded `ParcelSnapshot` (free-text `dimensions`, `Double weightKg`) and `DeliverySnapshot` | `Shipment` with flat recipient fields, `carrier`, `ShipmentItem` collection, integer parcel values, `declaredValue`, `currency` |
| Statuses | `REGISTERED, SHIPPED, DELIVERED, CANCELLED` | `TTN_PENDING, TTN_FAILED, TTN_GENERATED, IN_TRANSIT, DELIVERED, CANCELLED` |
| Identifier | `waybillNumber` | `ttn` |
| Endpoints | create consignment (public `POST`), patch waybill, track by waybill, cancel by `POST` | EP-DEL-01…03 (no public endpoint), the `OrderPaid` / `OrderShipped` consumers (EP-DEL-EVT-01/02) and the in-process carrier report (EP-DEL-04) |
| Who creates a shipment | an HTTP client posting a JPA entity | only the `OrderPaid` consumer |
| API model | JPA entity used as request and response body | separate request/response DTOs |
| Errors | bare `orElseThrow()` → `500` | typed exceptions mapped to `400`/`404`/`409`/`424` |
| Carrier | none | `CarrierGateway` port with an in-process `MockCarrierGateway` |
| Events | none | consumes `OrderPaid`, `OrderShipped`; emits `TTNGenerated`, `OrderDelivered` via an outbox |
| Schema | `ddl-auto: update`, Flyway starter present but no migrations | Flyway owns the schema, `ddl-auto: validate` |
| Tests | context-load test on `postgres:latest` | Testcontainers on `postgres:18-alpine` |

This task prepares the module so the following tasks add features on a clean base. It adds no endpoint.

*References:* [Delivery Service Spec](../../design/services-specs/delivery-service-spec.md), [Domain Entities — Delivery](../../design/entities/entities.md), [Inventory foundation task](../inventory-service/01-rework-draft-to-spec-foundation.md)

## Acceptance Criteria
- [ ] Remove the draft code that contradicts the spec: `DeliveryController`, `DeliveryService`, `ConsignmentRepository`, `Consignment`, `DeliverySnapshot`, `ParcelSnapshot`.
- [ ] Align the package layout with `inventory-service` (`controllers`, `services`, `repositories`, `entities`, `dto`, `exceptions`, `config`, `security`, `clients`, `events`, `outbox`), plus `carrier` for the carrier port and its mock (task 07).
- [ ] Update `pom.xml` against `inventory-service/pom.xml`: add `spring-boot-starter-validation` and the matching test starters; remove `spring-boot-devtools` if the other services do not use it. Security, Kafka and HTTP-client dependencies are added in their own tasks.
- [ ] `application.yml`: `ddl-auto: validate`; keep problem details and the actuator probes.
- [ ] Test `TestcontainersConfiguration` uses `postgres:18-alpine` (the version in `docker-compose.yml` and `k8s/`).
- [ ] A context-load smoke test passes against a real PostgreSQL.
- [ ] `.env.example` and `k8s/delivery/app-config.yaml` stay consistent (database settings only; the other variables come with tasks 04, 05 and 09).

## Technical Notes / Constraints
- Nothing outside this module calls the draft `/api/v1/delivery` endpoints from code, but the Postman collections `VoltStore_API_Tests.json` and `VoltStore_API_Tests2.json` do. Remove or mark those requests so they do not mislead.
- `order-service` has its own `DeliverySnapshot` entity (the recipient copy on the order). It belongs to Order's model and is unaffected.
- Avoid Lombok `@Data` on JPA entities; follow the entity convention of Catalog and Inventory.
- `target/` and `.idea/` are build artefacts; do not edit them by hand.
