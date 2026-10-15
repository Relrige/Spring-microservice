---
status: DONE
service: delivery-service
---
# Carrier Gateway Port and In-Process Mock

## Context
Delivery is an Anti-Corruption Layer: only it knows how a carrier works. Two ports keep the carrier behind VoltStore's own types: `CarrierGateway` (Delivery → carrier: register, cancel, hand over) and `CarrierEventHandler` (carrier → Delivery: a parcel was delivered).

VoltStore has no real carrier. The decision (2026-10-15) is an **in-process** `MockCarrierGateway`: no extra application, no network, and no webhook. After a handover the mock itself reports the delivery, a configurable delay later, by calling `CarrierEventHandler`. A separate simulator app with real network failures and a signed webhook is deferred ([task 17](17-future-carrier-simulator-and-webhook-signature.md)); it would add second adapters of the same two ports.
*References:* [Delivery Spec — Carrier Gateway, Future: Out-of-Process Carrier Simulator](../../design/services-specs/delivery-service-spec.md)

## Acceptance Criteria
- [x] Package `carrier`: `CarrierGateway` with `String register(ConsignmentRequest)`, `void cancel(String ttn)` and `void handOver(String ttn)`; the inbound port `CarrierEventHandler` with `void onDelivered(String ttn)` (implemented in [task 15](15-consume-order-shipped-and-delivery-report.md)); records `ConsignmentRequest` (orderId, carrier, recipient, parcel, declaredValue, currency), `Recipient`, `Parcel`; `CarrierException`.
- [x] `MockCarrierGateway` (the only `CarrierGateway` bean): `register` returns a random 14-digit TTN; `cancel` succeeds for any TTN.
- [x] `handOver(ttn)` schedules `CarrierEventHandler.onDelivered(ttn)` after `delivery.carrier.mock.delivery-delay` (default `30s`) on a `TaskScheduler`. A TTN already scheduled is not scheduled again. If `onDelivered` throws, the report is rescheduled after the same delay. `handOver` never fails.
- [x] Configuration (`@ConfigurationProperties`, validated): `delivery.carrier.mock.failure-rate` (`0.0`–`1.0`, default `0.0`) makes a call throw `CarrierException` with that probability; `delivery.carrier.mock.latency` (default `0`) delays each call. Both apply to `register` and `cancel`. `delivery.carrier.mock.delivery-delay` as above (e2e tests use about 1 s).
- [x] Every call is logged once at `INFO` (orderId or TTN, result), so a demo shows what the "carrier" did.
- [x] Unit tests: TTN format; failure rate `0` never fails; failure rate `1` always fails; invalid configuration fails at startup; `handOver` calls the handler once after the delay (controllable scheduler/clock); a second `handOver` for the same TTN does not schedule again; a throwing handler is called again later.

## Technical Notes / Constraints
- Inject a `RandomGenerator` (or `Random`) so tests are deterministic.
- The mock's schedule lives in memory and is lost on restart. Delivery re-announces `IN_TRANSIT` shipments at startup ([task 15](15-consume-order-shipped-and-delivery-report.md)), so `handOver` must stay idempotent.
- Avoid a circular dependency between the mock and the service implementing `CarrierEventHandler` (inject the handler lazily, e.g. `ObjectProvider<CarrierEventHandler>`).
- Nothing outside `carrier` may depend on `MockCarrierGateway` directly; callers use the interface. That is what makes the future simulator a drop-in replacement.

## Implementation Notes (2026-10-15)
- **Package `carrier`:** `CarrierGateway` (`register`, `cancel`, `handOver`), `CarrierEventHandler` (`onDelivered`), records `ConsignmentRequest`, `Recipient`, `Parcel` (the last two already from task 02), `CarrierException`, `MockCarrierProperties`, `MockCarrierGateway`.
- **`MockCarrierGateway`:** `register` returns `random.nextLong(10^13, 10^14)` (14 digits, no leading zero); `cancel` accepts any TTN; both sleep `latency` and throw `CarrierException("simulated carrier failure")` when `random.nextDouble() < failureRate`. `handOver` never fails: it adds the TTN to an in-memory set and schedules `onDelivered` at `clock.instant() + deliveryDelay` on Spring's `TaskScheduler`; a TTN already in the set is not scheduled again. After an accepted report the TTN is removed from the set (bounded memory; a later duplicate report is harmless because `onDelivered` is idempotent). A throwing handler is rescheduled after the same delay.
- **Circular dependency:** the handler is injected as `ObjectProvider<CarrierEventHandler>` and resolved only when a report runs. Until task 15 there is no implementation, so a report would be logged and rescheduled.
- **Configuration:** `delivery.carrier.mock.failure-rate` / `latency` / `delivery-delay` from `DELIVERY_CARRIER_MOCK_FAILURE_RATE` / `_LATENCY` / `_DELIVERY_DELAY` (defaults `0.0`, `0`, `30s`), validated (`0.0`–`1.0`, not null); documented in `.env.example` (the ConfigMap follows in task 16).
- **Supporting beans:** `config/ClockConfig` (`Clock.systemUTC()`, replaced in tests); `spring.task.scheduling.pool.size: 4`, because the outbox relay, the TTN job (task 11) and the mock's reports share Spring's scheduler, whose default is one thread.
- **Determinism:** a package-private constructor takes a `RandomGenerator`; the Spring constructor uses a `SecureRandom`, because it is thread-safe (fixed during task 15: a shared `RandomGenerator.getDefault()` gave two concurrent registrations the same TTN). Nothing outside `carrier` refers to `MockCarrierGateway`.
- **Verified (2026-10-15):** `MockCarrierGatewayTest` (TTN format and uniqueness, failure rate 0 and 1, report once after the delay through a captured scheduler task, no second schedule, rescheduling after a throwing handler, invalid `failure-rate` fails the context) passes; the context-load test still passes.
