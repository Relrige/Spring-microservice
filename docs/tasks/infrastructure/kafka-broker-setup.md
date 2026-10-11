---
status: DONE
service: infrastructure
---
# Kafka Broker for Local Compose and Kubernetes

## Context
The platform needs an asynchronous message broker (the Event Catalogue lists "RabbitMQ / Kafka"; the team chose Kafka). No broker exists in `docker-compose.yml` or `k8s/` yet. Catalog Service is the first user ([Kafka setup in catalog](../catalog-service/10-kafka-spring-setup-and-topic.md)); later Inventory, Order, Delivery, Customer and Notification reuse the same broker. This task only provides the broker; it contains no service code.
*References:* [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Catalog Spec — Events](../../design/services-specs/catalog-service-spec.md)

## Acceptance Criteria
- [x] `docker-compose.yml` has a single-node Kafka broker in KRaft mode (no ZooKeeper), with a persistent volume and a health check. Any service can reach it at one stable address inside the Compose network, and it is also reachable from the host (for running a service from the IDE) on a different listener/port.
- [x] Kubernetes manifests for the broker (StatefulSet or Deployment, Service, storage suitable for local minikube) are added under `k8s/kafka/` following the naming and label conventions of the existing folders. The broker is `ClusterIP` only and is not added to the Ingress.
- [x] The Kafka image and version are pinned (no `latest`) and the choice is noted in the manifests or this task.
- [x] Topic auto-creation is **disabled**: topics are declared by the owning services (see the catalog task), so a typo in a topic name fails loudly instead of silently creating a new topic.
- [x] Replication-related broker settings (offsets topic, transaction log) are set for a single broker so consumer groups work.
- [x] The broker's in-cluster and Compose addresses are documented (for example in a short comment block in the manifests), so services can set `SPRING_KAFKA_BOOTSTRAP_SERVERS`.
- [x] `docker compose up` starts the broker and it reports healthy; a manual produce/consume check with the broker's bundled console tools succeeds and is recorded in the PR.
- [x] `k8s.txt` is updated if applying `k8s/` recursively needs an ordering note.

## Technical Notes / Constraints
- **Why Kafka rather than a queue broker:** a log-based broker keeps messages after consumption, supports replay, and gives per-key ordering within a partition and consumer groups. That fits events such as `ProductStockReplenished` / `ProductStockDepleted` for the same product. Record this reasoning in the manifests or a short decision note.
- Kafka clients connect to the address returned in the broker's *advertised listeners*, not only the one they dialled. Getting separate internal (cluster/Compose) and external (host) advertised listeners right is the most common source of "connection works but nothing is received" problems. Verify both paths.
- Single broker means replication factor 1; topics declared by services must use it. This is acceptable for the study project and should be stated as a known limitation.
- Resource requests/limits should be small enough for a laptop running minikube next to five services and their databases.
- Do not add a Kafka UI or Schema Registry in this task. They can be added later if needed.
- **Image (decision):** `apache/kafka:4.1.0`, the official Apache Kafka image (KRaft by default, includes the console tools under `/opt/kafka/bin`). The same image is used by the Testcontainers tests.
- **Addresses:** Compose `kafka:9092` (listener `INTERNAL`), host/IDE `localhost:9094` (listener `EXTERNAL`), Kubernetes `kafka:9092` (Service `kafka`, ClusterIP). Documented in comments in `docker-compose.yml` and `k8s/kafka/kafka-service.yaml`.
- **Kubernetes detail:** the pod sets `enableServiceLinks: false`. Otherwise Kubernetes injects `KAFKA_PORT=tcp://...` for the `kafka` Service, and the image turns every `KAFKA_*` variable into a broker setting.
- **`CLUSTER_ID`** is fixed (generated with `kafka-storage.sh random-uuid`), so the formatted data volume is reused across restarts.
- **Verification:** `docker compose up -d kafka` → healthy. Console round-trip on `kafka:9092`: created topic `smoke-test`, produced `hello-voltstore`, consumed `hello-voltstore`. Producing to the undeclared `typo-topic` failed with `UNKNOWN_TOPIC_OR_PARTITION` (auto-creation disabled). `__consumer_offsets` is created, so consumer groups work. From the host (`localhost:9094`), the broker advertises `localhost:9094` and a consumer read the catalog events. The Kubernetes listener config was checked by running the same environment in a standalone container; it was **not** applied to minikube (the cluster was stopped).
