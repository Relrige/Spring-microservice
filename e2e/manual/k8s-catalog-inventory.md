# Catalog ↔ Inventory via Kafka: Kubernetes (minikube)

> Run successfully on 2026-10-13 (results in the
> [infrastructure task](../../docs/tasks/infrastructure/kafka-inventory-wiring-and-minikube-check.md)).

The same checks as [the Compose runbook](docker-compose-catalog-inventory.md), plus broker checks that only
matter in Kubernetes (volume, pod restart) and the consumer group with two replicas. Only a **minimal stack**
is deployed: the namespace, Kafka, Catalog and Inventory (each with its Postgres).

Shell: **Git Bash**, from the repository root (see [README](README.md)).

## 0. Prerequisites
- Stop Docker Compose first: minikube runs inside the same Docker Desktop VM and needs its memory.

```bash
docker compose stop
```

- A minikube cluster with enough resources. CPU and memory are fixed when the cluster is created; the defaults
  (2 CPUs, ~4 GB) are too small. Recreating **deletes everything in the cluster**:

```bash
minikube delete
minikube start --cpus=6 --memory=6g
```

  Later runs only need `minikube start`. The dashboard addon runs extra pods and is not needed:

```bash
minikube addons disable dashboard
```

## 1. Images
The Deployments use `voltstore/<service>:latest` with `imagePullPolicy: IfNotPresent`, so the images must be
copied into minikube (it has its own image store). Build them, or reuse images already built by Docker Compose:

```bash
docker build -t voltstore/catalog-service:latest ./catalog-service
docker build -t voltstore/inventory-service:latest ./inventory-service
```

or, if `docker compose up --build` was run on the current code:

```bash
docker tag spring-microservice-catalog-service:latest voltstore/catalog-service:latest
docker tag spring-microservice-inventory-service:latest voltstore/inventory-service:latest
```

Copy them into minikube (slow: each image is exported and imported, about a minute each):

```bash
minikube image load voltstore/catalog-service:latest
minikube image load voltstore/inventory-service:latest
```

After rebuilding an image with the same tag, load it again and restart the Deployment
(`kubectl rollout restart deployment/<name> -n voltstore`); the running pods keep the old image otherwise.

## 2. Deploy the minimal stack
Order matters: the namespace first; Kafka before the services (they start without it but log connection
warnings). The `*-template.yaml` Secrets contain local development values and are applied as they are.

```bash
kubectl apply -f k8s/base/
kubectl apply -f k8s/kafka/
kubectl apply -f k8s/catalog/
kubectl apply -f k8s/inventory/
```

## 3. Wait until everything runs
The broker pod becomes ready when port 9092 accepts connections; the services when their readiness probe is
`UP` (startup can take a couple of minutes on a laptop).

```bash
kubectl wait --for=condition=Ready pod/kafka-0 -n voltstore --timeout=300s
kubectl rollout status deployment/catalog-service -n voltstore --timeout=600s
kubectl rollout status deployment/inventory-service -n voltstore --timeout=600s
kubectl get pods -n voltstore
```

Expected: `kafka-0`, `catalog-service-…`, `inventory-service-…` and both `*-postgres-…` pods `Running` and
`1/1` ready. If a pod is not ready:
- `Pending`: not enough CPU/memory on the node → `kubectl describe pod <name> -n voltstore` (see *Events*).
- `CrashLoopBackOff`: the application fails → `kubectl logs <name> -n voltstore` (add `--previous` for the
  crashed container).

## 4. Broker storage and round trip
The StatefulSet asks for a 2 Gi volume through a PersistentVolumeClaim; minikube's storage provisioner should
create and bind it.

```bash
kubectl get pvc -n voltstore
```

Expected: `kafka-data-kafka-0` (and the two Postgres claims) with `STATUS = Bound`.

Produce and consume one message with the console tools inside the broker pod. Auto-creation is disabled, so the
test topic is created first and deleted afterwards:

```bash
MSYS_NO_PATHCONV=1 kubectl exec -n voltstore kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
  --create --topic smoke-test --partitions 1 --replication-factor 1
echo "hello-voltstore" | MSYS_NO_PATHCONV=1 kubectl exec -i -n voltstore kafka-0 -- /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 --topic smoke-test
MSYS_NO_PATHCONV=1 kubectl exec -n voltstore kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic smoke-test --from-beginning --timeout-ms 8000
MSYS_NO_PATHCONV=1 kubectl exec -n voltstore kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
  --delete --topic smoke-test
```

Expected: the consumer prints `hello-voltstore` (then the expected `TimeoutException`). This uses
`kafka:9092`, the broker's **advertised** address, so it also proves that the Service name resolves inside the
cluster.

## 5. Both topics exist

```bash
MSYS_NO_PATHCONV=1 kubectl exec -n voltstore kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

Expected: `catalog.product-events`, `inventory.stock-events`, `__consumer_offsets`.

## 6. Reach the services from the host
The services are `ClusterIP` (reachable only inside the cluster). `kubectl port-forward` tunnels a local port to
a Service. Started in the background here; stop them in step 11.

```bash
kubectl port-forward -n voltstore svc/catalog-service 8081:8081 > /dev/null 2>&1 &
CATALOG_PF=$!
kubectl port-forward -n voltstore svc/inventory-service 8084:8084 > /dev/null 2>&1 &
INVENTORY_PF=$!
```

```bash
for port in 8081 8084; do
  echo "port $port: $(curl -s --retry 10 --retry-delay 2 --retry-all-errors --max-time 5 http://localhost:$port/actuator/health/readiness)"
done
```

Expected: `{"status":"UP"}` for both. (The ports collide with Docker Compose's published ports, which is why
Compose must be stopped.)

## 7. Catalog → Kafka → Inventory: a new product gets a StockItem

```bash
CATEGORY_ID=$(curl -s -X POST http://localhost:8081/catalog/categories \
  -H 'Content-Type: application/json' \
  -d '{"name":"Manual e2e k8s '"$(date +%s)"'"}' | grep -o '"categoryId":"[^"]*"' | cut -d'"' -f4)
echo "CATEGORY_ID=$CATEGORY_ID"
```

```bash
PRODUCT_ID=$(curl -s -X POST http://localhost:8081/catalog/products \
  -H 'Content-Type: application/json' \
  -d '{"title":"Manual e2e laptop","description":"Kafka check","categoryId":"'"$CATEGORY_ID"'","basePrice":999.99}' \
  | grep -o '"productId":"[^"]*"' | cut -d'"' -f4)
echo "PRODUCT_ID=$PRODUCT_ID"
```

Query Inventory's database inside its Postgres pod:

```bash
kubectl exec -n voltstore deploy/inventory-service-postgres -- psql -U inventory-service -d inventory-service \
  -c "select product_id, available_quantity, reserved_quantity, gross_weight_grams from stock_items where product_id = '$PRODUCT_ID'"
```

Expected: one row, quantities `0`, packaging empty.

```bash
kubectl logs -n voltstore deploy/inventory-service | grep "StockItem created for product $PRODUCT_ID"
```

## 8. Inventory → Kafka: stock events

```bash
curl -s -X POST "http://localhost:8084/inventory/stock/$PRODUCT_ID/adjust" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'X-User-Role: INVENTORY_WORKER' \
  -d '{"delta":10}'; echo
```

```bash
MSYS_NO_PATHCONV=1 kubectl exec -n voltstore kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic inventory.stock-events --from-beginning --timeout-ms 8000 \
  --property print.key=true --property print.headers=true --property print.partition=true --property print.offset=true \
  | grep "$PRODUCT_ID"
```

Expected: `{"newAvailableQuantity":10}`, then one record with `eventType:ProductStockReplenished` and the
product ID as key.

## 9. Two replicas share one consumer group
Every Deployment runs one replica to fit the local cluster. Scale Inventory to two to see Kafka split the 3
partitions of Catalog's topic between the two pods of group `inventory-service` (each message is then handled by
exactly one pod).

```bash
kubectl scale deployment inventory-service -n voltstore --replicas=2
kubectl rollout status deployment/inventory-service -n voltstore --timeout=600s
MSYS_NO_PATHCONV=1 kubectl exec -n voltstore kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group inventory-service
```

Expected: two different `CONSUMER-ID`s / `HOST`s, one owning 2 partitions and the other 1. Scale back:

```bash
kubectl scale deployment inventory-service -n voltstore --replicas=1
```

## 10. Broker restart keeps its data
Deleting the pod makes the StatefulSet recreate `kafka-0` with the same volume and the fixed `CLUSTER_ID`, so the
topics and messages survive.

```bash
kubectl delete pod kafka-0 -n voltstore
kubectl wait --for=condition=Ready pod/kafka-0 -n voltstore --timeout=300s
MSYS_NO_PATHCONV=1 kubectl exec -n voltstore kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

Expected: both service topics are still listed. Repeating step 8's consumer shows the earlier stock event.

## 11. Stop
Stop the port-forwards:

```bash
kill $CATALOG_PF $INVENTORY_PF
```

Either keep the cluster's state and only stop the node:

```bash
minikube stop
```

or remove the whole deployment (namespace, pods, volumes and their data) and then stop:

```bash
kubectl delete namespace voltstore
minikube stop
```
