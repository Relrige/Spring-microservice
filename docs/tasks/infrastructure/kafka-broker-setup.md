---
status: TODO
service: infrastructure
---
# Kafka Broker for Local Compose and Kubernetes

## Context
The platform needs an asynchronous message broker (the Event Catalogue lists "RabbitMQ / Kafka"; the team chose Kafka). No broker exists in `docker-compose.yml` or `k8s/` yet. Catalog Service is the first user ([Kafka setup in catalog](../catalog-service/10-kafka-spring-setup-and-topic.md)); later Inventory, Order, Delivery, Customer and Notification reuse the same broker. This task only provides the broker; it contains no service code.
*References:* [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Catalog Spec — Events](../../design/services-specs/catalog-service-spec.md)

## Acceptance Criteria
- [ ] `docker-compose.yml` has a single-node Kafka broker in KRaft mode (no ZooKeeper), with a persistent volume and a health check. Any service can reach it at one stable address inside the Compose network, and it is also reachable from the host (for running a service from the IDE) on a different listener/port.
- [ ] Kubernetes manifests for the broker (StatefulSet or Deployment, Service, storage suitable for local minikube) are added under `k8s/kafka/` following the naming and label conventions of the existing folders. The broker is `ClusterIP` only and is not added to the Ingress.
- [ ] The Kafka image and version are pinned (no `latest`) and the choice is noted in the manifests or this task.
- [ ] Topic auto-creation is **disabled**: topics are declared by the owning services (see the catalog task), so a typo in a topic name fails loudly instead of silently creating a new topic.
- [ ] Replication-related broker settings (offsets topic, transaction log) are set for a single broker so consumer groups work.
- [ ] The broker's in-cluster and Compose addresses are documented (for example in a short comment block in the manifests), so services can set `SPRING_KAFKA_BOOTSTRAP_SERVERS`.
- [ ] `docker compose up` starts the broker and it reports healthy; a manual produce/consume check with the broker's bundled console tools succeeds and is recorded in the PR.
- [ ] `k8s.txt` is updated if applying `k8s/` recursively needs an ordering note.

## Technical Notes / Constraints
- **Why Kafka rather than a queue broker:** a log-based broker keeps messages after consumption, supports replay, and gives per-key ordering within a partition and consumer groups. That fits events such as `ProductStockReplenished` / `ProductStockDepleted` for the same product. Record this reasoning in the manifests or a short decision note.
- Kafka clients connect to the address returned in the broker's *advertised listeners*, not only the one they dialled. Getting separate internal (cluster/Compose) and external (host) advertised listeners right is the most common source of "connection works but nothing is received" problems. Verify both paths.
- Single broker means replication factor 1; topics declared by services must use it. This is acceptable for the study project and should be stated as a known limitation.
- Resource requests/limits should be small enough for a laptop running minikube next to five services and their databases.
- Do not add a Kafka UI or Schema Registry in this task. They can be added later if needed.
