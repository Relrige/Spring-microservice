# Checkout and Order Lifecycle through the Gateway: Docker Compose

Checks the whole order flow with every service running: validate → submit → pay → TTN → ship → delivered, plus a
declined card, cancelling a paid order and an unpaid order that expires. Every Order call goes **through the
gateway only** (`http://localhost:8080`); the gateway sets `X-User-Id`, `X-User-Role` and `X-User-Email` from the
JWT. Only Customer's cart is called directly, because its gateway routes are a separate change.

```
client --JWT--> api-gateway :8080 --> order --> catalog (batch-get) / inventory (reserve) / payment (charge)
                                        \--Kafka--> inventory, delivery, customer;  delivery --Kafka--> order
```

Shell: **Git Bash**, from the repository root (see [README](README.md)). `G` holds the gateway address.

## 0. Prerequisites

- Local `.env` files for all eight services (copy each `.env.example`). `AUTH_JWT_SECRET` must match in
  `api-gateway` and `auth-service`; the internal token must match in every service.
- Short timings make the run faster. Add to `order-service/.env` (the grace must be longer than
  `PAYMENT_READ_TIMEOUT`, 8 s by default, or Order refuses to start):
  ```
  ORDER_PAYMENT_WINDOW=1m
  ORDER_EXPIRY_INTERVAL=10s
  ORDER_EXPIRY_GRACE=10s
  ```
  and to `delivery-service/.env`: `DELIVERY_CARRIER_MOCK_DELIVERY_DELAY=10s`.
- Order's old draft volume has no Flyway history and must be reset once (see
  [Order task 16](../../docs/tasks/order-service/16-containerization-and-deployment.md#still-open)).

## 1. Start the stack

```bash
docker compose up -d --build
for port in 8086 8081 8084 8085 8083 8087 8082; do
  echo "port $port: $(curl -sf --retry 40 --retry-delay 3 --retry-all-errors --max-time 5 http://localhost:$port/actuator/health/readiness)"
done
G=http://localhost:8080
code() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }
body() { curl -s -w '\n%{http_code}\n' "$@"; }
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
json=(-H 'Content-Type: application/json')
RUN=$(date +%s)
```

Expected: `{"status":"UP"}` seven times.

## 2. Users, product and stock

```bash
login() { curl -s -X POST $G/auth/login "${json[@]}" -d '{"email":"'$1'","password":"'$2'"}' | field token; }
code -X POST $G/user/register "${json[@]}" -d '{"email":"Alice'$RUN'@Example.com","password":"secret123"}'
code -X POST $G/user/register "${json[@]}" -d '{"email":"bob'$RUN'@example.com","password":"secret123"}'
ALICE=$(login alice$RUN@example.com secret123); BOB=$(login bob$RUN@example.com secret123)
ADMIN=$(login admin@voltstore.com admin-local-password)
for r in manager:CATALOG_MANAGER worker:INVENTORY_WORKER; do
  code -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer $ADMIN" \
    -d '{"email":"'${r%%:*}$RUN'@example.com","password":"secret123","role":"'${r##*:}'"}'
done
MANAGER=$(login manager$RUN@example.com secret123); WORKER=$(login worker$RUN@example.com secret123)
AUTHA=(-H "Authorization: Bearer $ALICE"); AUTHB=(-H "Authorization: Bearer $BOB")
AUTHM=(-H "Authorization: Bearer $MANAGER"); AUTHW=(-H "Authorization: Bearer $WORKER")
ALICE_ID=$(echo $ALICE | cut -d. -f2 | base64 -d 2>/dev/null | field sub)
echo $ALICE | cut -d. -f2 | base64 -d 2>/dev/null; echo
```

Expected: `201` twice and `201` twice; the decoded token shows `"email":"alice<RUN>@example.com"` (lower case,
although Alice registered and could log in with capitals). If `base64` complains about padding, the claims are
still printed.

```bash
CATEGORY_ID=$(curl -s -X POST $G/catalog/categories "${json[@]}" "${AUTHM[@]}" -d '{"name":"Order e2e '$RUN'"}' | field categoryId)
PRODUCT_ID=$(curl -s -X POST $G/catalog/products "${json[@]}" "${AUTHM[@]}" \
  -d '{"title":"Order e2e laptop","description":"e2e","categoryId":"'$CATEGORY_ID'","basePrice":999.99}' | field productId)
sleep 3
code -X PUT $G/inventory/stock/$PRODUCT_ID/packaging "${json[@]}" "${AUTHW[@]}" \
  -d '{"grossWeightGrams":2100,"lengthCm":40,"widthCm":30,"heightCm":8}'
body -X POST $G/inventory/stock/$PRODUCT_ID/adjust "${json[@]}" "${AUTHW[@]}" -d '{"delta":5}'
code -X PATCH $G/catalog/products/$PRODUCT_ID/status "${json[@]}" "${AUTHM[@]}" -d '{"status":"ACTIVE"}'
sleep 3; body $G/catalog/products/$PRODUCT_ID
```

Expected: `204`, `{"newAvailableQuantity":5}`, `200`, and the product with `"stockStatus":"IN_STOCK"`.

## 3. Happy path

Put the product into Alice's cart (directly, with hand-made trusted headers), validate and submit:

```bash
stock() { curl -s $G/inventory/stock "${AUTHW[@]}" | grep -o "{[^{}]*\"productId\":\"$PRODUCT_ID\"[^{}]*}"; }
status() { curl -s $G/orders/$1 "${AUTHA[@]}" | field status; }
ITEMS='[{"productId":"'$PRODUCT_ID'","quantity":1}]'
DELIVERY='{"name":"Alice Test","phone":"+380501234567","address":"Kyiv, Khreshchatyk 1","carrier":"NOVA_POSHTA"}'
card() { echo '{"cardDetails":{"number":"'$1'","expiryMonth":12,"expiryYear":2030,"cvv":"123"}}'; }
submit() { curl -s -X POST $G/orders "${json[@]}" "${AUTHA[@]}" -d '{"items":'"$ITEMS"',"deliveryDetails":'"$DELIVERY"'}' | field orderId; }

body -X PUT localhost:8087/customers/cart "${json[@]}" -H "X-User-Id: $ALICE_ID" -H 'X-User-Role: CUSTOMER' -d '{"items":'"$ITEMS"'}'
code -X POST $G/orders/validate "${json[@]}" "${AUTHA[@]}" -d '{"items":'"$ITEMS"'}'
ORDER_ID=$(submit); echo "ORDER_ID=$ORDER_ID"; status $ORDER_ID; stock
```

Expected: the cart with one item; validate `204`; `PENDING_PAYMENT`; stock `available 4, reserved 1, total 5`.

```bash
body -X POST $G/orders/$ORDER_ID/pay "${json[@]}" "${AUTHA[@]}" -d "$(card 4242424242424242)"
sleep 3; curl -s localhost:8087/customers/cart -H "X-User-Id: $ALICE_ID" -H 'X-User-Role: CUSTOMER'; echo
status $ORDER_ID
body $G/orders/fulfillment-queue "${AUTHW[@]}"
```

Expected: `{"orderId":...,"status":"PAID"}` `200`; the cart is `[]`; within a few seconds `PENDING_SHIPPING` (Delivery
generated a TTN); the queue answers `deliveryDataAvailable: true` and lists the order with `TTN_GENERATED` and its
`ttn`.

```bash
body -X POST $G/orders/$ORDER_ID/ship "${AUTHW[@]}"
sleep 3; stock
sleep 12; body $G/orders/$ORDER_ID "${AUTHA[@]}"
```

Expected: `SHIPPED` with the TTN; stock `available 4, reserved 0, total 4` (Inventory consumed the reservation;
it is an event, so allow a few seconds); after the carrier delay `"status":"DELIVERED"`, `"deliveryStatus":"DELIVERED"`.

## 4. Declined card, cancellation, expiry

```bash
DECLINED_ID=$(submit)
body -X POST $G/orders/$DECLINED_ID/pay "${json[@]}" "${AUTHA[@]}" -d "$(card 4000000000000002)"; status $DECLINED_ID
```

Expected: `402` with `"reason":"insufficient_funds"`; the order stays `PENDING_PAYMENT`.

```bash
CANCEL_ID=$(submit); code -X POST $G/orders/$CANCEL_ID/pay "${json[@]}" "${AUTHA[@]}" -d "$(card 4242424242424242)"
sleep 4; status $CANCEL_ID
body -X DELETE $G/orders/$CANCEL_ID "${AUTHA[@]}"
sleep 5; body $G/orders/$CANCEL_ID "${AUTHA[@]}"; stock
docker logs payment-service 2>&1 | grep "refund:$CANCEL_ID"
```

Expected: `200`, `PENDING_SHIPPING`; `202` `CANCELLED` (or `CANCELLATION_REQUESTED` while a step is retried);
details `"deliveryStatus":"CANCELLED"`; the order's reservation is released; Payment logs an approved refund.

```bash
EXPIRE_ID=$(submit); stock
for i in $(seq 1 20); do s=$(status $EXPIRE_ID); [ "$s" = PENDING_PAYMENT ] || break; sleep 6; done; echo "$s"; stock
body -X POST $G/orders/$EXPIRE_ID/pay "${json[@]}" "${AUTHA[@]}" -d "$(card 4242424242424242)"
```

Expected: after the payment window plus grace (about 80 s with the settings above) `EXPIRED`; the stock is back to
`available 4, reserved 0` (the declined order of this section expires too); paying now is `409`.

## 5. Access through the gateway

```bash
for req in "POST /orders/validate" "POST /orders" "GET /orders" "GET /orders/$ORDER_ID" "DELETE /orders/$ORDER_ID" \
           "POST /orders/$ORDER_ID/pay" "GET /orders/fulfillment-queue" "POST /orders/$ORDER_ID/ship"; do
  set -- $req; echo "$req -> $(code -X $1 "$G$2" "${json[@]}" -d '{}')"
done
code $G/orders/$ORDER_ID "${AUTHB[@]}"                                   # 404: another customer's order
code -X DELETE $G/orders/$ORDER_ID "${AUTHB[@]}"                         # 404
code $G/orders/$ORDER_ID "${AUTHB[@]}" -H "X-User-Id: $ALICE_ID"         # 404: forged header is replaced
code $G/orders -H "X-User-Id: $ALICE_ID" -H 'X-User-Role: CUSTOMER' -H 'X-User-Email: a@b.c'   # 401
code $G/orders/fulfillment-queue "${AUTHA[@]}"                           # 403
code -X POST $G/orders "${json[@]}" "${AUTHW[@]}" -d '{}'                # 403
code $G/orders -H "Authorization: Bearer $ADMIN"                         # 403
code $G/orders/not-a-uuid "${AUTHA[@]}"                                  # 404 (no route)
docker logs api-gateway 2>&1 | grep -ci "alice$RUN@"; docker logs order-service 2>&1 | grep -ci "alice$RUN@"
```

Expected: `401` for all eight lines of the loop, then the commented codes; the email appears in neither log (`0`, `0`).
