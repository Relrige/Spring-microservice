---
status: TODO
service: inventory-service
---
# Spring Kafka Setup and Declarative Topic

## Context
Inventory Service emits `ProductStockReplenished` / `ProductStockDepleted` and later consumes `ProductCreated` and `OrderShipped`. This task connects the service to the broker and declares the topic it owns as code. No business event is sent yet ([task 06](06-stock-events-publish-and-listen.md) does that); the goal is a verified connection.

**Depends on:** [Kafka Broker for Local Compose and Kubernetes](../infrastructure/kafka-broker-setup.md). The same setup was done for Catalog; reuse its decisions instead of redoing them: [Catalog Spring Kafka setup](../catalog-service/10-kafka-spring-setup-and-topic.md).
*References:* [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Inventory Spec — Events](../../design/services-specs/inventory-service-spec.md)

## Acceptance Criteria
- [ ] Add the Spring Kafka starter (and its test support) to `pom.xml`, with versions managed by the Spring Boot 4.1 parent.
- [ ] Bootstrap servers come from `SPRING_KAFKA_BOOTSTRAP_SERVERS`, documented in `.env.example`, `docker-compose.yml` and `k8s/inventory/app-config.yaml`; `inventory-service` waits for the broker to be healthy in Compose.
- [ ] The topic for stock events is declared **in configuration code** with a `NewTopic` bean built by `TopicBuilder`, with **3 partitions** and a replication factor valid for the single broker. Topic names are constants (or properties) in one place.
- [ ] JSON (de)serialization configured for producer and consumer, with string keys (the product UUID), tolerant of unknown properties and independent of Java type headers carrying the producer's class name.
- [ ] Integration tests start Kafka with Testcontainers (`@ServiceConnection`) next to PostgreSQL, reusing the pattern in `TestcontainersConfiguration`.
- [ ] Connection smoke test: the context starts, the topic exists with exactly 3 partitions (admin client), and a message sent with `KafkaTemplate` is received by a test listener.
- [ ] Behaviour when the broker is down at application start is checked and documented (the service must still start; readiness must not depend on Kafka unless the team decides otherwise).

## Technical Notes / Constraints
- **Topic layout decision (shared with Catalog's task 10 and its consumer task):** `ProductStockReplenished` and `ProductStockDepleted` for the same product must be applied in order by Catalog, and Kafka guarantees order only within a partition. Put **both events on one topic** (suggested name `inventory.stock-events`) and use `productId` as the message key. Use the naming convention chosen in Catalog's task 10 and write the final topic name into both services' configuration.
- The producing service owns and declares its topics: Inventory declares the stock-events topic. It only **subscribes** to topics owned by Catalog (`ProductCreated`) and Order (`OrderShipped`); their names are configuration properties, and listeners must not crash the application if such a topic does not exist yet (see [task 15](15-consume-product-created.md)).
- 3 partitions: events of one product land in one partition (ordering kept) while up to three consumers of a group can share the load. Put that reasoning in a comment next to the bean.
- Keep Kafka configuration in its own `config` class, separate from business code.
