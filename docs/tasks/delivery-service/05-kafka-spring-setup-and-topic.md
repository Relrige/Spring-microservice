---
status: TODO
service: delivery-service
---
# Kafka Spring Setup and Shipment Events Topic

## Context
Delivery consumes `OrderPaid` and `OrderShipped` from Order's topic and emits `TTNGenerated` and `OrderDelivered` on its own topic. This task adds the Kafka wiring that the outbox (task 06) and the consumers (tasks 10 and 15) build on, following the setup already used by Catalog and Inventory.
*References:* [Delivery Spec — Shipment Events, Configuration](../../design/services-specs/delivery-service-spec.md), [Event Catalogue — Delivery Updates](../../design/services-requirements/events-catalogue.md), [Inventory Kafka setup task](../inventory-service/05-kafka-spring-setup-and-topic.md)

## Acceptance Criteria
- [ ] Add `spring-kafka` (and the Kafka test dependencies) as in `inventory-service`.
- [ ] `SPRING_KAFKA_BOOTSTRAP_SERVERS` with the same defaults as the other services (`localhost:9094` locally, `kafka:9092` in Compose and Kubernetes).
- [ ] Delivery declares its topic with a `NewTopic` bean: `delivery.shipment-events`, 3 partitions, key `orderId`. The name is a code constant (`KafkaTopics`).
- [ ] Producer settings stated explicitly: `acks=all`, `enable.idempotence=true`, `max.block.ms=5000`; values are JSON without Java type headers.
- [ ] Consumer settings: group `delivery-service`, `auto-offset-reset: earliest`, JSON read as `String` and converted by a `StringJacksonJsonMessageConverter`-style converter (same as Inventory), unknown properties ignored.
- [ ] `ORDER_EVENTS_TOPIC` property (default `order.order-events`, provisional) for the consumed topic; Delivery does **not** declare Order's topic.
- [ ] A Testcontainers Kafka test configuration with topic auto-creation disabled, as in Inventory.
- [ ] Test: the context starts with the broker, and the shipment-events topic exists with 3 partitions.

## Technical Notes / Constraints
- Reuse Inventory's `KafkaConfig` structure; do not invent a different serialization.
- The `eventType` header is part of the wire format for both topics.
