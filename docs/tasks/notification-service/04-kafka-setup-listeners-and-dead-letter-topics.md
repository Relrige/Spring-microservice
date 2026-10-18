---
status: DONE
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
- [x] Topic names from configuration: `notification.kafka.topics.order-events` ← `ORDER_EVENTS_TOPIC` (default `order.order-events`) and `notification.kafka.topics.delivery-events` ← `DELIVERY_EVENTS_TOPIC` (default `delivery.shipment-events`). Neither source topic is declared by Notification.
- [x] Notification declares its dead-letter topics, 1 partition each: `notification.order.order-events.dlt` and `notification.delivery.shipment-events.dlt` (naming as in `KafkaTopics.deadLetterTopicOf` in Customer).
- [x] `OrderEventsListener` and `DeliveryEventsListener` take the raw `ConsumerRecord<String, String>`, read the `eventType` header and call a handler. Unknown types and records without the header are logged at `DEBUG` and acknowledged. On the Delivery topic, `TTNGenerated` is skipped.
- [x] `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` that sends each failed record to the dead-letter topic of its source topic. `PoisonMessageException` is not retryable (sent at once); other exceptions get 4 retries with back-off 1 s, 2 s, 4 s, 8 s, then go to the DLT.
- [x] A shared JSON reader that parses the value into the handler's own record type, with unknown properties ignored, and runs Bean Validation. Invalid JSON or a broken constraint → `PoisonMessageException`.
- [x] `ack-mode: batch` and `missing-topics-fatal: false` set explicitly.
- [x] Tests (Testcontainers Kafka, with stub handlers): each listener receives only its topic's types; an unknown `eventType` and a missing header are skipped; a poison record reaches the right DLT without retries and does not block a later record of the same partition; a handler that fails twice and then succeeds is retried and not dead-lettered; the service starts and readiness is `UP` when both source topics are missing.

## Technical Notes / Constraints
- Blocking retries (not `@RetryableTopic`) keep the order of events per key, as in Customer and Delivery. Ordering is not critical here, but the service then behaves like its neighbours. Note this in the implementation notes.
- Enum-like payload fields (`reason`) are read as `String`, so a new value never fails deserialisation (tolerant reader).
- Log `eventId`, `orderId`, topic and offset; never the payload, because it contains email addresses and, for `OrderPaid`, recipient details.

## Implementation Notes (2026-10-18)
- **Topics:** `notification.kafka.topics.order-events` / `delivery-events` from `ORDER_EVENTS_TOPIC` / `DELIVERY_EVENTS_TOPIC`. `KafkaConfig` declares only the two DLTs (`KafkaTopics.deadLetterTopicOf` → `notification.<source>.dlt`, 1 partition).
- **Dispatch design (deviation from Customer/Delivery, which use `if/else` in the listener):** each event type has its own handler bean implementing `EventHandler.OrderEventHandler<T>` or `EventHandler.DeliveryEventHandler<T>` (`eventType()`, `payloadType()`, `handle(T)`). `OrderEventsListener` and `DeliveryEventsListener` each build an `EventDispatcher` from their handlers: it reads the header, looks the handler up, reads the payload with `EventReader` and calls the handler. A type with no handler, or a record with no header, is logged at `DEBUG` and acknowledged. So `TTNGenerated` is skipped simply because nobody handles it.
  - Reasons for the change: there are five types on two topics, and the commits of tasks 06 and 07 only add handler beans. It also lets this task's tests plug in stub handlers. Two handlers for one type fail at startup.
- **Listeners:** `@KafkaListener(id = "order-events" / "delivery-events", idIsGroup = false)`. The id names the container for tests; without `idIsGroup = false` it would replace the group `notification-service`.
- **`EventReader`:** Jackson `readerFor(type)` with unknown properties ignored, then Bean Validation. Anything that cannot become a valid event throws `PoisonMessageException`:
  - not JSON → `not valid JSON`;
  - a wrong value type → `invalid value at <path>`. Jackson's own message quotes the value, which may be an email address, so it is dropped and no cause is attached. The record itself is in the DLT.
  - broken constraints → the property paths and messages, never the values.
- **Error handler:** a copy of Customer's `DefaultErrorHandler`. The recoverer sends each record to the DLT of its own source topic. `PoisonMessageException` is not retryable; other exceptions get `ExponentialBackOffWithMaxRetries(4)` (1/2/4/8 s). The log line names topic, key, partition and offset only.
- **Blocking retries rather than `@RetryableTopic`:** this keeps the order of events per key, as in Customer and Delivery. Ordering is not critical for emails, but the service then behaves like its neighbours, and a short outage costs at most ~15 s per record. Sending email is not in the listener, so SMTP never causes these retries.
- `application.yml`: string serializers, group `notification-service`, `auto-offset-reset: earliest`, `ack-mode: batch`, `missing-topics-fatal: false`. `.env.example` lists the Kafka variables.
- **Verified (2026-10-18):**
  - `EventReaderTest` (unit): tolerant read, not JSON, `null`, a wrong type without the value in the message, constraint list.
  - `EventListenersIntegrationTest` (Testcontainers Kafka + PostgreSQL, stub handlers):
    - each listener handles only its own topic's types;
    - unknown type, `TTNGenerated` and a missing header are skipped;
    - three poison records reach the right DLT in under 10 s (no retries) and do not block later records of the same key;
    - a handler that fails twice is called 3 times and not dead-lettered.
  - `MissingSourceTopicsStartupTest`: without the source topics both containers run in group `notification-service`, the DLTs exist and readiness is `UP`.
