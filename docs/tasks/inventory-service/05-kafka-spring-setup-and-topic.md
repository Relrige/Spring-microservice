---
status: DONE
service: inventory-service
---
# Spring Kafka Setup and Declarative Topic

## Context
Inventory Service emits `ProductStockReplenished` / `ProductStockDepleted` and later consumes `ProductCreated` and `OrderShipped`. This task connects the service to the broker and declares the topic it owns as code. No business event is sent yet ([task 06](06-stock-events-publish-and-listen.md) does that); the goal is a verified connection.

**Depends on:** [Kafka Broker for Local Compose and Kubernetes](../infrastructure/kafka-broker-setup.md). The same setup was done for Catalog; reuse its decisions instead of redoing them: [Catalog Spring Kafka setup](../catalog-service/10-kafka-spring-setup-and-topic.md).
*References:* [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Inventory Spec — Events](../../design/services-specs/inventory-service-spec.md)

## Acceptance Criteria
- [x] Add the Spring Kafka starter (and its test support) to `pom.xml`, with versions managed by the Spring Boot 4.1 parent.
- [ ] Bootstrap servers come from `SPRING_KAFKA_BOOTSTRAP_SERVERS`, documented in `.env.example`, `docker-compose.yml` and `k8s/inventory/app-config.yaml`; `inventory-service` waits for the broker to be healthy in Compose.
- [x] The topic for stock events is declared **in configuration code** with a `NewTopic` bean built by `TopicBuilder`, with **3 partitions** and a replication factor valid for the single broker. Topic names are constants (or properties) in one place.
- [x] JSON (de)serialization configured for producer and consumer, with string keys (the product UUID), tolerant of unknown properties and independent of Java type headers carrying the producer's class name.
- [x] Integration tests start Kafka with Testcontainers (`@ServiceConnection`) next to PostgreSQL, reusing the pattern in `TestcontainersConfiguration`.
- [x] Connection smoke test: the context starts, the topic exists with exactly 3 partitions (admin client), and a message sent with `KafkaTemplate` is received by a test listener.
- [x] Behaviour when the broker is down at application start is checked and documented (the service must still start; readiness must not depend on Kafka unless the team decides otherwise).

## Technical Notes / Constraints
- **Topic layout decision (shared with Catalog's task 10 and its consumer task):** `ProductStockReplenished` and `ProductStockDepleted` for the same product must be applied in order by Catalog, and Kafka guarantees order only within a partition. Put **both events on one topic** (suggested name `inventory.stock-events`) and use `productId` as the message key. Use the naming convention chosen in Catalog's task 10 and write the final topic name into both services' configuration.
- The producing service owns and declares its topics: Inventory declares the stock-events topic. It only **subscribes** to topics owned by Catalog (`ProductCreated`) and Order (`OrderShipped`); their names are configuration properties, and listeners must not crash the application if such a topic does not exist yet (see [task 15](15-consume-product-created.md)).
- 3 partitions: events of one product land in one partition (ordering kept) while up to three consumers of a group can share the load. Put that reasoning in a comment next to the bean.
- Keep Kafka configuration in its own `config` class, separate from business code.

## Implementation Notes / Deviations
- **Deferred to the broker task:** the Compose and Kubernetes wiring (bootstrap address in `docker-compose.yml` and `k8s/inventory/app-config.yaml`, and `depends_on` with a healthy broker) is not done because no broker exists yet. The first half of that criterion is done: `SPRING_KAFKA_BOOTSTRAP_SERVERS` is read by `application.yml` (default `localhost:9092`) and documented in `.env.example` (host address; the Compose address comes with the broker task).
- **Consumer deserialization:** values are read as `String` (`StringDeserializer`) and a `StringJacksonJsonMessageConverter` bean (`KafkaConfig`) converts the JSON to the type of each `@KafkaListener` method parameter. This lets one consumer handle several event types ([task 15](15-consume-product-created.md), [task 16](16-consume-order-shipped.md)) and ignores unknown properties and type headers. The producer uses `JacksonJsonSerializer` with `spring.json.add.type.headers: false`.
- **Topic:** `inventory.stock-events` (constant in `KafkaTopics`), 3 partitions, 1 replica.
- **Test containers:** split into `PostgresTestContainerConfig` and `KafkaTestContainerConfig` (`apache/kafka:4.1.0`, topic auto-creation disabled). `@DataJpaTest` repository tests use only PostgreSQL.
- **Broker down at start (verified by `KafkaBrokerDownStartupTest`):** the application starts and `/actuator/health/readiness` stays `UP`, because Spring Boot has no Kafka health indicator and topic creation failure is only logged. Producing (`KafkaTemplate.send`) would block until the producer timeout, which matters for later tasks.
