---
status: TODO
service: notification-service
---
# Kafka Setup: Two Listeners, Dead-Letter Topics and Error Handling

## Context
Notification consumes two topics owned by other services: Order's `order.order-events` and Delivery's `delivery.shipment-events`. Each topic gets **one** listener in group `notification-service`, which dispatches by the `eventType` header. Two listeners in one group on the same topic would split its partitions, and each would drop the other's event types.

This task builds the wiring that tasks 06 and 07 fill with handlers:
- two listeners that route each record to a handler by event type and skip unknown types;
- Notification's own dead-letter topics;
- the error handler that separates poison messages from transient failures.

The closest model is Customer Service: also a pure consumer, with no outbox and no own event topic.
*References:* [Notification Spec — Consuming events](../../design/services-specs/notification-service-spec.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Customer `OrderPaid` consumer task](../customer-service/08-consume-order-paid.md), [Delivery `OrderPaid` consumer task](../delivery-service/10-consume-order-paid.md)

## Acceptance Criteria
- [ ] Topic names from configuration: `notification.kafka.topics.order-events` ← `ORDER_EVENTS_TOPIC` (default `order.order-events`) and `notification.kafka.topics.delivery-events` ← `DELIVERY_EVENTS_TOPIC` (default `delivery.shipment-events`). Neither source topic is declared by Notification.
- [ ] Notification declares its dead-letter topics, 1 partition each: `notification.order.order-events.dlt` and `notification.delivery.shipment-events.dlt` (naming as in `KafkaTopics.deadLetterTopicOf` in Customer).
- [ ] `OrderEventsListener` and `DeliveryEventsListener` take the raw `ConsumerRecord<String, String>`, read the `eventType` header and call a handler. Unknown types and records without the header are logged at `DEBUG` and acknowledged. On the Delivery topic, `TTNGenerated` is skipped.
- [ ] `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` that sends each failed record to the dead-letter topic of its source topic. `PoisonMessageException` is not retryable (sent at once); other exceptions get 4 retries with back-off 1 s, 2 s, 4 s, 8 s, then go to the DLT.
- [ ] A shared JSON reader that parses the value into the handler's own record type, with unknown properties ignored, and runs Bean Validation. Invalid JSON or a broken constraint → `PoisonMessageException`.
- [ ] `ack-mode: batch` and `missing-topics-fatal: false` set explicitly.
- [ ] Tests (Testcontainers Kafka, with stub handlers): each listener receives only its topic's types; an unknown `eventType` and a missing header are skipped; a poison record reaches the right DLT without retries and does not block a later record of the same partition; a handler that fails twice and then succeeds is retried and not dead-lettered; the service starts and readiness is `UP` when both source topics are missing.

## Technical Notes / Constraints
- Blocking retries (not `@RetryableTopic`) keep the order of events per key, as in Customer and Delivery. Ordering is not critical here, but the service then behaves like its neighbours. Note this in the implementation notes.
- Enum-like payload fields (`reason`) are read as `String`, so a new value never fails deserialisation (tolerant reader).
- Log `eventId`, `orderId`, topic and offset; never the payload, because it contains email addresses and, for `OrderPaid`, recipient details.
