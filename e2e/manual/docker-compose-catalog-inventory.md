# Catalog ↔ Inventory via Kafka: Docker Compose

Checks that Catalog and Inventory run in Docker Compose, reach the Kafka broker at `kafka:9092`, and exchange
events:

```
Catalog  --ProductCreated-->           catalog.product-events  -->  Inventory creates an empty StockItem
Inventory --ProductStockReplenished--> inventory.stock-events   -->  (Catalog consumer: Catalog task 18, not built yet)
```

Shell: **Git Bash**, from the repository root (see [README](README.md)).

## 0. Prerequisites
- Docker Desktop is running. If minikube is running too, stop it (`minikube stop`) to free memory.
- Local `.env` files exist and point at the broker's Compose address. Compare them with `.env.example`; both
  need `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` (inside a container, `localhost` is the container itself):

```bash
grep KAFKA catalog-service/.env inventory-service/.env
```

- **Old volumes:** if a service fails at startup with a Flyway error such as `relation "products" already exists`,
  its Postgres volume still holds a schema from before the Flyway migrations. Recreate it (this **deletes all
  local data** of the two services):

```bash
docker compose rm -s -f catalog-service inventory-service catalog-service-postgres inventory-service-postgres
docker volume rm spring-microservice_catalog-service-postgres spring-microservice_inventory-service-postgres
```

## 1. Start the stack
Builds both images from the current code (Maven runs inside Docker, the first build takes a few minutes) and
starts them with their databases and the broker. `depends_on` makes the services wait for a healthy `kafka`.

```bash
docker compose up -d --build kafka catalog-service inventory-service
```

```bash
docker compose ps
```

Expected: `kafka` is `healthy`, both `*-pg` are `healthy`, both services are `Up` (not `Exited`). If a service
exited, read its log: `docker logs catalog-service` / `docker logs inventory-service`.

## 2. Wait until both services are ready
Spring Boot's readiness probe answers `UP` once the application context has started. The loop retries for up to
about 2 minutes.

```bash
for port in 8081 8084; do
  echo "port $port: $(curl -s --retry 40 --retry-delay 3 --retry-all-errors --max-time 5 http://localhost:$port/actuator/health/readiness)"
done
```

Expected: `port 8081: {"status":"UP"}` (Catalog) and `port 8084: {"status":"UP"}` (Inventory).

## 3. Both topics exist
Broker auto-creation is disabled, so a topic exists only if its owning service connected and declared it. This
proves both services reached the broker.

```bash
MSYS_NO_PATHCONV=1 docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

Expected: `catalog.product-events` (declared by Catalog), `inventory.stock-events` (declared by Inventory) and
`__consumer_offsets` (Kafka's internal topic for committed consumer offsets).

Details of one topic (3 partitions, replication factor 1):

```bash
MSYS_NO_PATHCONV=1 docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic catalog.product-events
```

## 4. Catalog → Kafka → Inventory: a new product gets a StockItem
Create a category, then a product in it. Catalog has no security yet, so no headers are needed. The IDs are
kept in shell variables for the next steps.

```bash
CATEGORY_ID=$(curl -s -X POST http://localhost:8081/catalog/categories \
  -H 'Content-Type: application/json' \
  -d '{"name":"Manual e2e '"$(date +%s)"'"}' | grep -o '"categoryId":"[^"]*"' | cut -d'"' -f4)
echo "CATEGORY_ID=$CATEGORY_ID"
```

```bash
PRODUCT_ID=$(curl -s -X POST http://localhost:8081/catalog/products \
  -H 'Content-Type: application/json' \
  -d '{"title":"Manual e2e laptop","description":"Kafka check","categoryId":"'"$CATEGORY_ID"'","basePrice":999.99}' \
  | grep -o '"productId":"[^"]*"' | cut -d'"' -f4)
echo "PRODUCT_ID=$PRODUCT_ID"
```

If an ID is empty, run the `curl` without the `| grep ...` part to see the error response.

Catalog publishes `ProductCreated` after its database transaction commits; Inventory's `ProductCreatedListener`
consumes it and inserts a `StockItem`. This happens asynchronously, so look a second later. Query Inventory's
database directly:

```bash
docker exec inventory-service-pg psql -U inventory-service -d inventory-service \
  -c "select product_id, available_quantity, reserved_quantity, gross_weight_grams from stock_items where product_id = '$PRODUCT_ID'"
```

Expected: one row with `available_quantity = 0`, `reserved_quantity = 0` and empty packaging (`gross_weight_grams`
is null).

The same, seen in the logs of both sides:

```bash
docker logs inventory-service 2>&1 | grep "StockItem created for product $PRODUCT_ID"
```

```bash
docker logs catalog-service 2>&1 | grep "Received ProductCreated" | tail -n 3
```

The Catalog line comes from its temporary demo listener (Catalog task 11), which reads Catalog's own topic.

Read the raw event on the topic (key = productId, header `eventType`). The consumer stops after 8 s without new
messages; the `TimeoutException` printed at the end is expected:

```bash
MSYS_NO_PATHCONV=1 docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic catalog.product-events --from-beginning --timeout-ms 8000 \
  --property print.key=true --property print.headers=true --property print.partition=true --property print.offset=true
```

## 5. Inventory → Kafka: stock crossing zero publishes events
Inventory reads the caller from trusted headers set by the API Gateway; here they are set by hand.
`0 → 10` publishes `ProductStockReplenished`.

```bash
curl -s -X POST "http://localhost:8084/inventory/stock/$PRODUCT_ID/adjust" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'X-User-Role: INVENTORY_WORKER' \
  -d '{"delta":10}'; echo
```

Expected: `{"newAvailableQuantity":10}`.

`10 → 0` publishes `ProductStockDepleted`:

```bash
curl -s -X POST "http://localhost:8084/inventory/stock/$PRODUCT_ID/adjust" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'X-User-Role: INVENTORY_WORKER' \
  -d '{"delta":-10}'; echo
```

Read the stock events of this product:

```bash
MSYS_NO_PATHCONV=1 docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic inventory.stock-events --from-beginning --timeout-ms 8000 \
  --property print.key=true --property print.headers=true --property print.partition=true --property print.offset=true \
  | grep "$PRODUCT_ID"
```

Expected: two records, `eventType:ProductStockReplenished` then `eventType:ProductStockDepleted`, both with the
product ID as key and in the **same partition** with increasing offsets (same key → same partition → ordered).

## 6. Consumer group of Inventory
Shows which consumers of group `inventory-service` own which partitions of Catalog's topic and how far they have
committed.

```bash
MSYS_NO_PATHCONV=1 docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group inventory-service
```

Expected: one consumer owns all 3 partitions of `catalog.product-events`; `LAG` is `0` on every partition
(`-` means nothing was committed on that partition yet, i.e. no message landed there).

## 7. Stop
Keeps containers and volumes (data survives):

```bash
docker compose stop
```

Removes containers but keeps volumes:

```bash
docker compose down
```
