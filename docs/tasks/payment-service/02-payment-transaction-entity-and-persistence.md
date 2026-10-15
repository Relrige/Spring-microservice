---
status: TODO
service: payment-service
---
# PaymentTransaction Entity and Persistence

## Context
`PaymentTransaction` is the only aggregate of the service: an **append-only** history of what was asked of the payment gateway and what it answered. A charge and its refund are two rows; rows are never updated after insert. The table is also the place where the idempotency guards of EP-PAY-01 and EP-PAY-02 ("never double-charge, never double-refund") are enforced as a data rule.
*References:* [Domain Entities — Payment](../../design/entities/entities.md), [Payment Spec](../../design/services-specs/payment-service-spec.md), [Inventory reservation persistence task](../inventory-service/12-stock-reservation-entity-and-persistence.md)

## Acceptance Criteria
- [ ] Flyway migration `V1__create_payment_transactions_table.sql` creates `payment_transactions` with: `id` (UUID PK), `order_id` (UUID, not null), `type` (`CHARGE`/`REFUND`, not null, `CHECK`), `status` (`SUCCESS`/`FAILED`, not null, `CHECK`), `amount` (`NUMERIC(12,2)`, not null, `CHECK > 0`), `currency` (`CHAR(3)`, not null), `gateway_transaction_id` (nullable), `failure_reason` (nullable, a fixed set of codes, see below), `idempotency_key` (not null; the key sent to the gateway, see [task 06](06-charge-card.md)), `created_at` (`TIMESTAMPTZ`, not null).
- [ ] `failure_reason` codes separate **definitive** outcomes (`insufficient_funds`, `card_declined`) from **uncertain** ones (`gateway_timeout`, `gateway_unavailable`). The attempt counter of task 06 depends on this split, so it is an enum in code and a `CHECK` in the table.
- [ ] **Partial unique indexes** (last-resort data guard): at most one `SUCCESS` `CHARGE` per `order_id`, and at most one `SUCCESS` `REFUND` per `order_id`. Any number of `FAILED` rows is allowed (a customer may retry with another card).
- [ ] Index on `order_id` for the lookups of tasks 06 and 07.
- [ ] Entity `PaymentTransaction` with enums `TransactionType { CHARGE, REFUND }` and `TransactionStatus { SUCCESS, FAILED }`, mapped as strings; no setters for fields that must not change after insert (factory methods such as `successfulCharge(...)`, `failedCharge(...)` are one option).
- [ ] Hibernate `validate` passes against the migration.
- [ ] Repository methods needed by tasks 06–07, for example `existsByOrderIdAndTypeAndStatus` and `findByOrderIdAndTypeAndStatus`.
- [ ] Repository tests on PostgreSQL (Testcontainers): save and read back; the second `SUCCESS CHARGE` for an order violates the index; two `FAILED` charges for one order are accepted; `amount <= 0` violates the `CHECK`.

## Technical Notes / Constraints
- `failure_reason` and `idempotency_key` are not in the entity list of the spec (EP-PAY-01 step 5 only says "with a timeout reason"). Add both to `entities.md` when this task is done.
- Use `BigDecimal` for money and `Instant` (`TIMESTAMPTZ`) for time; never `double`. Decide the scale (2 digits for UAH) once here.
- No card data is stored in this table, not even masked; see [task 08](08-card-data-protection.md).
- The entity must not use `@Data` (equality and `toString` over all fields are a problem on JPA entities).
