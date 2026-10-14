# API Gateway with Auth, Catalog and Inventory: Docker Compose

Checks the edge of the system: every request goes **through the gateway only** (`http://localhost:8080`). Auth
Service issues the tokens, the gateway verifies them, enforces the route table and sets the trusted headers, and
Catalog and Inventory rely on those headers.

```
client --JWT--> api-gateway :8080 --X-User-Id / X-User-Role--> auth | catalog | inventory
```

Shell: **Git Bash**, from the repository root (see [README](README.md)). `G` holds the gateway address.

## 0. Prerequisites
- Docker Desktop is running; minikube is stopped (`minikube stop`) to free memory.
- Local `.env` files exist for `api-gateway`, `auth-service`, `catalog-service` and `inventory-service` (copy each
  `.env.example` if missing). The JWT key must be **identical** in the gateway and Auth Service, and the internal
  token identical in the three services:

```bash
grep -h AUTH_JWT_SECRET api-gateway/.env auth-service/.env
grep -h "INTERNAL_TOKEN" auth-service/.env catalog-service/.env inventory-service/.env
grep GATEWAY_UPSTREAMS api-gateway/.env
```

Expected: two equal `AUTH_JWT_SECRET` lines; three equal tokens; upstreams `http://<service>:8080` (inside Compose
the container port, not the published host port).

- If a service fails with a Flyway error such as `relation "..." already exists`, its volume predates the
  migrations: see the [Catalog ↔ Inventory runbook](docker-compose-catalog-inventory.md#0-prerequisites).

## 1. Start the stack

```bash
docker compose up -d --build kafka api-gateway auth-service catalog-service inventory-service
```

The gateway does not wait for anything (it is stateless); until an upstream is up, its routes answer `502`.
Wait until the services are ready (their host ports are used only for the readiness check):

```bash
for port in 8086 8081 8084; do
  echo "port $port: $(curl -sf --retry 40 --retry-delay 3 --retry-all-errors --max-time 5 http://localhost:$port/actuator/health/readiness)"
done
G=http://localhost:8080
```

Expected: `{"status":"UP"}` three times.

## 2. Auth through the gateway
`code` prints only the status; `body` prints the status line and the body.

```bash
code() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }
body() { curl -s -w '\n%{http_code}\n' "$@"; }
json=(-H 'Content-Type: application/json')
RUN=$(date +%s)
```

Register a customer (`201`), then the same email again (`409` from Auth Service, passed through unchanged:
`"service":"auth-service"`):

```bash
body -X POST $G/user/register "${json[@]}" -d '{"email":"customer'$RUN'@example.com","password":"secret123"}'
body -X POST $G/user/register "${json[@]}" -d '{"email":"customer'$RUN'@example.com","password":"secret123"}'
```

Log in (`200` with a token); wrong password → `401` `"Invalid credentials"` with `"service":"auth-service"`:

```bash
login() { curl -s -X POST $G/auth/login "${json[@]}" -d '{"email":"'$1'","password":"'$2'"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4; }
CUSTOMER=$(login customer$RUN@example.com secret123); echo "${CUSTOMER:0:20}..."
body -X POST $G/auth/login "${json[@]}" -d '{"email":"customer'$RUN'@example.com","password":"wrong-password"}'
```

The seeded administrator (`AUTH_ADMIN_EMAIL` / `AUTH_ADMIN_PASSWORD` in `auth-service/.env`):

```bash
ADMIN=$(login admin@voltstore.com admin-local-password); echo "${ADMIN:0:20}..."
```

## 3. Admin-only route: `POST /user/non-customer`

```bash
staff() { echo '{"email":"'$1$RUN'@example.com","password":"secret123","role":"'$2'"}'; }
code -X POST $G/user/non-customer "${json[@]}" -d "$(staff m CATALOG_MANAGER)"                                  # 401 (gateway)
code -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer $CUSTOMER" -d "$(staff m CATALOG_MANAGER)"  # 403 (gateway)
code -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer garbage.token.value" -d "$(staff m CATALOG_MANAGER)"  # 401
code -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer ${ADMIN}x" -d "$(staff m CATALOG_MANAGER)"   # 401 (bad signature)
```

Gateway errors are problem details with `"service":"api-gateway"` and never say why a token was rejected:

```bash
body -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer $CUSTOMER" -d '{}'
```

Forged identity headers do not elevate privileges (`403` with a customer token, `401` without a token):

```bash
code -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer $CUSTOMER" \
  -H 'X-User-Role: ADMIN' -H 'X-User-Id: 00000000-0000-0000-0000-000000000001' -d "$(staff x CATALOG_MANAGER)"
code -X POST $G/user/non-customer "${json[@]}" -H 'X-User-Role: ADMIN' \
  -H 'X-User-Id: 00000000-0000-0000-0000-000000000001' -d "$(staff x CATALOG_MANAGER)"
```

The admin creates a catalog manager and an inventory worker (`201` each), who then log in:

```bash
code -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer $ADMIN" -d "$(staff manager CATALOG_MANAGER)"
code -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer $ADMIN" -d "$(staff worker INVENTORY_WORKER)"
MANAGER=$(login manager$RUN@example.com secret123)
WORKER=$(login worker$RUN@example.com secret123)
echo "${MANAGER:0:20}... ${WORKER:0:20}..."
```

## 4. Catalog and Inventory through the gateway
Wrong roles are stopped at the gateway (`403`), before Catalog or Inventory see the request:

```bash
code -X POST $G/catalog/categories "${json[@]}" -H "Authorization: Bearer $CUSTOMER" -d '{"name":"x"}'   # 403
code -X POST $G/catalog/categories "${json[@]}" -H "Authorization: Bearer $ADMIN" -d '{"name":"x"}'      # 403 (roles are not hierarchical)
code $G/inventory/stock -H "Authorization: Bearer $MANAGER"                                            # 403
```

The catalog manager creates a category and a product (Catalog sees the manager's identity only through the
gateway's headers):

```bash
AUTHM=(-H "Authorization: Bearer $MANAGER")
CATEGORY_ID=$(curl -s -X POST $G/catalog/categories "${json[@]}" "${AUTHM[@]}" -d '{"name":"Gateway e2e '$RUN'"}' \
  | grep -o '"categoryId":"[^"]*"' | cut -d'"' -f4); echo "CATEGORY_ID=$CATEGORY_ID"
PRODUCT_ID=$(curl -s -X POST $G/catalog/products "${json[@]}" "${AUTHM[@]}" \
  -d '{"title":"Gateway e2e laptop","description":"through the gateway","categoryId":"'$CATEGORY_ID'","basePrice":999.99}' \
  | grep -o '"productId":"[^"]*"' | cut -d'"' -f4); echo "PRODUCT_ID=$PRODUCT_ID"
```

The new product is `NOT_ACTIVE`, so a guest does not see it (`404`), while the manager does (`200`, Optional route
with a valid token):

```bash
code $G/catalog/products/$PRODUCT_ID
code "$G/catalog/products?status=NOT_ACTIVE&search=Gateway%20e2e" "${AUTHM[@]}"
```

`ProductCreated` reached Inventory through Kafka. The inventory worker sees the stock item, sets packaging and
adds stock:

```bash
AUTHW=(-H "Authorization: Bearer $WORKER")
sleep 2; body $G/inventory/stock "${AUTHW[@]}" | grep -o "$PRODUCT_ID" | head -1
body -X PUT $G/inventory/stock/$PRODUCT_ID/packaging "${json[@]}" "${AUTHW[@]}" \
  -d '{"grossWeightGrams":2100,"lengthCm":40,"widthCm":30,"heightCm":8}'
body -X POST $G/inventory/stock/$PRODUCT_ID/adjust "${json[@]}" "${AUTHW[@]}" -d '{"delta":5}'
```

Expected: the product ID is printed; packaging `200`/`204`; `{"newAvailableQuantity":5}`.

The manager activates the product. Catalog checks readiness by calling Inventory **directly** with the internal
token (service-to-service, not through the gateway):

```bash
body -X PATCH $G/catalog/products/$PRODUCT_ID/status "${json[@]}" "${AUTHM[@]}" -d '{"status":"ACTIVE"}'
```

A guest now reads it; `stockStatus` is `IN_STOCK` (set from Inventory's `ProductStockReplenished` event):

```bash
body $G/catalog/products/$PRODUCT_ID
```

## 5. Optional vs Public routes with a bad token
`GET /catalog/products` is Optional: a stale or garbled token is rejected (`401`) instead of silently becoming a
guest. `GET /catalog/products/{id}` is Public: the token is ignored (`200`).

```bash
code $G/catalog/products -H 'Authorization: Bearer garbage.token.value'                  # 401
code $G/catalog/products/$PRODUCT_ID -H 'Authorization: Bearer garbage.token.value'      # 200
code $G/catalog/products                                                                 # 200 (guest)
```

## 6. Internal and unrouted paths are `404`
Internal endpoints exist in the services but are not in the gateway's route table; Actuator is on the gateway's
unpublished management port:

```bash
for req in "GET /catalog/products/batch-get" "GET /inventory/stock/$PRODUCT_ID/readiness" \
           "GET /inventory/stock/$PRODUCT_ID/packaging" "POST /inventory/reservations" \
           "POST /payments/charge" "GET /actuator/health" "GET /actuator/env" "GET /no/such/path" \
           "DELETE /catalog/products/$PRODUCT_ID" "GET /catalog/products/not-a-uuid"; do
  set -- $req; echo "$req -> $(code -X $1 "$G$2" "${json[@]}" -H "Authorization: Bearer $ADMIN" -d '{}')"
done
```

Expected: `404` for every line, also with an admin token.

## 7. Upstream down
With Inventory stopped, its routes answer `502` with a generic gateway problem (no host names):

```bash
docker compose stop inventory-service
body $G/inventory/stock "${AUTHW[@]}"
docker compose start inventory-service
```

## 8. Stop

```bash
docker compose stop
```
