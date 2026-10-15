---
status: TODO
service: delivery-service
---
# Carrier Gateway Port and In-Process Mock

## Context
Delivery is an Anti-Corruption Layer: only it knows how a carrier works. Two ports keep the carrier behind VoltStore's own types: `CarrierGateway` (Delivery → carrier: register, cancel, hand over) and `CarrierEventHandler` (carrier → Delivery: a parcel was delivered).

VoltStore has no real carrier. The decision (2026-10-15) is an **in-process** `MockCarrierGateway`: no extra application, no network, and no webhook. After a handover the mock itself reports the delivery, a configurable delay later, by calling `CarrierEventHandler`. A separate simulator app with real network failures and a signed webhook is deferred ([task 17](17-future-carrier-simulator-and-webhook-signature.md)); it would add second adapters of the same two ports.
*References:* [Delivery Spec — Carrier Gateway, Future: Out-of-Process Carrier Simulator](../../design/services-specs/delivery-service-spec.md)

## Acceptance Criteria
- [ ] Package `carrier`: `CarrierGateway` with `String register(ConsignmentRequest)`, `void cancel(String ttn)` and `void handOver(String ttn)`; the inbound port `CarrierEventHandler` with `void onDelivered(String ttn)` (implemented in [task 15](15-consume-order-shipped-and-delivery-report.md)); records `ConsignmentRequest` (orderId, carrier, recipient, parcel, declaredValue, currency), `Recipient`, `Parcel`; `CarrierException`.
- [ ] `MockCarrierGateway` (the only `CarrierGateway` bean): `register` returns a random 14-digit TTN; `cancel` succeeds for any TTN.
- [ ] `handOver(ttn)` schedules `CarrierEventHandler.onDelivered(ttn)` after `delivery.carrier.mock.delivery-delay` (default `30s`) on a `TaskScheduler`. A TTN already scheduled is not scheduled again. If `onDelivered` throws, the report is rescheduled after the same delay. `handOver` never fails.
- [ ] Configuration (`@ConfigurationProperties`, validated): `delivery.carrier.mock.failure-rate` (`0.0`–`1.0`, default `0.0`) makes a call throw `CarrierException` with that probability; `delivery.carrier.mock.latency` (default `0`) delays each call. Both apply to `register` and `cancel`. `delivery.carrier.mock.delivery-delay` as above (e2e tests use about 1 s).
- [ ] Every call is logged once at `INFO` (orderId or TTN, result), so a demo shows what the "carrier" did.
- [ ] Unit tests: TTN format; failure rate `0` never fails; failure rate `1` always fails; invalid configuration fails at startup; `handOver` calls the handler once after the delay (controllable scheduler/clock); a second `handOver` for the same TTN does not schedule again; a throwing handler is called again later.

## Technical Notes / Constraints
- Inject a `RandomGenerator` (or `Random`) so tests are deterministic.
- The mock's schedule lives in memory and is lost on restart. Delivery re-announces `IN_TRANSIT` shipments at startup ([task 15](15-consume-order-shipped-and-delivery-report.md)), so `handOver` must stay idempotent.
- Avoid a circular dependency between the mock and the service implementing `CarrierEventHandler` (inject the handler lazily, e.g. `ObjectProvider<CarrierEventHandler>`).
- Nothing outside `carrier` may depend on `MockCarrierGateway` directly; callers use the interface. That is what makes the future simulator a drop-in replacement.
