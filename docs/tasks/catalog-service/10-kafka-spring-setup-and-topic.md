---
status: TODO
service: catalog-service
---
# Spring Kafka Setup and Declarative Topic

## Context
Catalog Service will emit `ProductCreated` and later consume stock events. This task connects the service to the broker and declares the first topic as code. No business event is sent yet ([task 11](11-product-created-publish-and-listen.md) does that); the goal is a verified connection.

**Depends on:** [Kafka Broker for Local Compose and Kubernetes](../infrastructure/kafka-broker-setup.md).
*References:* [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Catalog Spec — Events](../../design/services-specs/catalog-service-spec.md)

## Acceptance Criteria
- [ ] Add the Spring Kafka starter (and its test support) to `pom.xml`, using the versions managed by the Spring Boot 4.1 parent.
- [ ] Bootstrap servers come from `SPRING_KAFKA_BOOTSTRAP_SERVERS`, documented in `.env.example`, `docker-compose.yml` and `k8s/catalog/app-config.yaml`; `catalog-service` waits for the broker to be healthy in Compose.
- [ ] The topic is declared **in configuration code** with a `NewTopic` bean built by `TopicBuilder`, with **3 partitions** and a replication factor valid for the single broker. Topic names are constants in one place.
- [ ] JSON (de)serialization is configured for producer and consumer (check the serializer class names for the Spring Kafka version in Boot 4.1; Jackson 3 is used). Keys are strings (the product UUID). Consumers ignore unknown properties and do not depend on Java type headers carrying the producer's class name.
- [ ] Integration tests start Kafka with Testcontainers (`@ServiceConnection`), reusing the pattern used for PostgreSQL in `TestcontainersConfiguration`.
- [ ] A connection smoke test: the application context starts, the topic exists with exactly 3 partitions (assert through the admin client), a message sent with `KafkaTemplate` to it is received by a test listener.
- [ ] Behaviour when the broker is down at application start is checked and documented (the service must still start and its readiness probe must not depend on Kafka unless the team decides otherwise).

## Technical Notes / Constraints
- **Topic naming and layout decision (needed here, used by tasks 11 and 18):** one topic per event type vs. one topic per aggregate. Kafka guarantees order only within a partition, and a partition is chosen from the message key. Events that must be applied in order for one product (`ProductStockReplenished` then `ProductStockDepleted`) therefore need the same topic and `productId` as key. Decide the naming convention (for example `catalog.product-events`) and record it. The producing service owns and declares its topics.
- Why 3 partitions: with the key `productId`, all events of one product land in one partition (ordering is preserved), while three partitions allow up to three consumers of one group to share the load. Mention this reasoning in a comment next to the bean.
- Keep the Kafka configuration in a `config` class of its own, separate from business code.
- Do not add Kafka to other services in this task.
