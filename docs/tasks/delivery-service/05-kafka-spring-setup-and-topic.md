---
status: DONE
service: delivery-service
---
# Kafka Spring Setup and Shipment Events Topic

## Context
Delivery consumes `OrderPaid` and `OrderShipped` from Order's topic and emits `TTNGenerated` and `OrderDelivered` on its own topic. This task adds the Kafka wiring that the outbox (task 06) and the consumers (tasks 10 and 15) build on, following the setup already used by Catalog and Inventory.
*References:* [Delivery Spec — Shipment Events, Configuration](../../design/services-specs/delivery-service-spec.md), [Event Catalogue — Delivery Updates](../../design/services-requirements/events-catalogue.md), [Inventory Kafka setup task](../inventory-service/05-kafka-spring-setup-and-topic.md)

## Acceptance Criteria
- [x] Add `spring-kafka` (and the Kafka test dependencies) as in `inventory-service`.
- [x] `SPRING_KAFKA_BOOTSTRAP_SERVERS` with the same defaults as the other services (`localhost:9094` locally, `kafka:9092` in Compose and Kubernetes).
- [x] Delivery declares its topic with a `NewTopic` bean: `delivery.shipment-events`, 3 partitions, key `orderId`. The name is a code constant (`KafkaTopics`).
- [x] Producer settings stated explicitly: `acks=all`, `enable.idempotence=true`, `max.block.ms=5000`; values are JSON without Java type headers.
- [x] Consumer settings: group `delivery-service`, `auto-offset-reset: earliest`, JSON read as `String` and converted by a `StringJacksonJsonMessageConverter`-style converter (same as Inventory), unknown properties ignored.
- [x] `ORDER_EVENTS_TOPIC` property (default `order.order-events`, provisional) for the consumed topic; Delivery does **not** declare Order's topic.
- [x] A Testcontainers Kafka test configuration with topic auto-creation disabled, as in Inventory.
- [x] Test: the context starts with the broker, and the shipment-events topic exists with 3 partitions.

## Technical Notes / Constraints
- Reuse Inventory's `KafkaConfig` structure; do not invent a different serialization.
- The `eventType` header is part of the wire format for both topics.

## Implementation Notes (2026-10-15)
- **Dependencies:** `spring-boot-starter-kafka`, `spring-kafka-test` and `testcontainers-kafka`, as in Inventory.
- **`application.yml`:** the `spring.kafka` block is Inventory's (producer `acks=all`, `enable.idempotence=true`, `max.block.ms=5000`, no type headers; consumer group `delivery-service`, `auto-offset-reset: earliest`, values read as `String`). The consumed topic is `delivery.kafka.topics.order-events` = `${ORDER_EVENTS_TOPIC:order.order-events}`; Delivery does not declare it.
- **`config/KafkaTopics`:** `SHIPMENT_EVENTS = "delivery.shipment-events"`, `EVENT_TYPE_HEADER`, `EVENT_ID_HEADER`. **`config/KafkaConfig`:** the `NewTopic` (3 partitions, 1 replica) and the `StringJacksonJsonMessageConverter`. The second (text) `KafkaTemplate`, the error handler and the DLT are added by task 10, where the first listener needs them.
- `SPRING_KAFKA_BOOTSTRAP_SERVERS` and `ORDER_EVENTS_TOPIC` added to `.env.example` and `k8s/delivery/app-config.yaml`.
- **Tests:** `KafkaTestContainerConfig` copied from Inventory (`apache/kafka:4.1.0`, topic auto-creation disabled); the context-load test now starts with both containers.
- **Verified (2026-10-15):** `KafkaConnectionTest` (topic exists with 3 partitions; a JSON round trip through the converter) passes.
