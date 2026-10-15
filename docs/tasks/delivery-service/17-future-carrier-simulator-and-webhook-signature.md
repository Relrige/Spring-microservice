---
status: TODO
service: delivery-service
---
# (Deferred) Out-of-Process Carrier Simulator and Signed Webhook

## Context
**Not planned for the current sprint.** On 2026-10-15 the team chose the in-process `MockCarrierGateway` ([task 07](07-carrier-gateway-port-and-mock.md)) because it is the easiest option. This task records the alternative so it can be picked up later.

The in-process mock has two limits:
- **Its failures are fake.** Retries and timeouts are tested against a Java method that throws, not a network that is slow or down.
- **There is no carrier-to-Delivery HTTP boundary.** The mock reports deliveries through a Java interface (`CarrierEventHandler`), so a webhook and its authentication cannot be practised.

A separate simulator app turns the carrier into a real external system. That brings back a webhook, and with it signature verification that actually protects something.
*References:* [Delivery Spec — Future: Out-of-Process Carrier Simulator](../../design/services-specs/delivery-service-spec.md)

## Acceptance Criteria
- [ ] New minimal application `carrier-simulator`: in-memory consignments (no database, no Kafka); `POST /consignments` → TTN, `DELETE /consignments/{ttn}`; failure injection (error rate, latency) from configuration.
- [ ] The simulator reports deliveries by calling Delivery's new webhook `POST /delivery/carrier-webhook` through the gateway's public route, like a real external caller: automatically after a delay following `POST /consignments/{ttn}/handover`, or at once via a control endpoint `POST /sim/consignments/{ttn}/deliver`.
- [ ] The webhook is a second adapter of the inbound port: it validates `{ ttn, status }`, calls `CarrierEventHandler.onDelivered(ttn)` for `DELIVERED`, and answers `200` also for unknown TTNs or other statuses (a `404` would make a real carrier retry). EP-DEL-04 itself does not change.
- [ ] Deployed as its own Deployment/Service in Compose and Kubernetes (optionally in a separate namespace to show the trust boundary).
- [ ] Delivery gets a second `CarrierGateway` implementation (declarative HTTP client with timeouts, including `handOver`), selected by configuration; the in-process mock stays for unit tests. With the simulator, the startup re-announcement of `IN_TRANSIT` shipments is no longer needed (the simulator keeps its own state); keep it harmless or switch it off for that profile.
- [ ] Webhook signature: the simulator sends `X-Timestamp` and `X-Signature = HMAC-SHA256(secret, timestamp + "." + rawBody)`. Delivery verifies over the **raw** request bytes, compares in constant time, and rejects stale timestamps (e.g. older than 5 min) with `401`. The secret comes from a Kubernetes Secret shared by both pods.
- [ ] Tests: a valid signature is accepted; a tampered body, a wrong secret and a stale timestamp → `401`; real network timeouts trigger the TTN job's retries.
- [ ] Spec, API contracts, gateway spec (route table: `POST /delivery/carrier-webhook`, `Public`) and Delivery's security chain (`permitAll` for the webhook; the signature is the authentication) updated.

## Technical Notes / Constraints
- Signature verification belongs in Delivery (the ACL), not in the gateway: the signing scheme is a carrier protocol detail.
- With a real network, registration should send an idempotency key (`orderId`) so a retried request after a lost response does not create a second TTN (spec *Open Issues*).
