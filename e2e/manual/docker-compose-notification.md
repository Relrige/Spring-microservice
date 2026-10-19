# Order Lifecycle Emails: Docker Compose

Checks that one customer journey through the gateway produces exactly the expected emails, at the account's
address, with no duplicates (US-CUST-12, SCN-S09). Notification consumes Order's and Delivery's events and sends
through **Mailpit**, whose REST API (`http://localhost:8025`) is used for the checks; its UI shows the same mails.

```
client --JWT--> api-gateway --> order --Kafka order.order-events--------> notification --SMTP--> mailpit :8025
                                  \--> inventory / payment / delivery --Kafka delivery.shipment-events--^
```

Shell: **Git Bash**, from the repository root (see [README](README.md)). The setup is the one of the
[Order runbook](docker-compose-order.md); its helper functions are repeated here so this runbook runs on its own.

## 0. Prerequisites

- Everything from the [Order runbook](docker-compose-order.md#0-prerequisites): `.env` files for all services
  (now nine: copy `notification-service/.env.example` too), and the short timings in `order-service/.env`
  (`ORDER_PAYMENT_WINDOW=1m`, `ORDER_EXPIRY_INTERVAL=10s`, `ORDER_EXPIRY_GRACE=10s`) and `delivery-service/.env`
  (`DELIVERY_CARRIER_MOCK_DELIVERY_DELAY=10s`).
- Volumes created before Order and Payment used Flyway fail their `V1` migration (see the Order runbook). To keep
  existing data, run the stack as a separate Compose project with its own volumes:
  `docker compose -p notification-e2e up -d --build` (stop the default project first; container names are fixed).
- Mailpit keeps messages in memory; the checks below filter by order ID, so older mails do not matter.

## 1. Start the stack

```bash
docker compose up -d --build
for port in 8086 8081 8084 8085 8083 8087 8082 8088; do
  echo "port $port: $(curl -sf --retry 40 --retry-delay 3 --retry-all-errors --max-time 5 http://localhost:$port/actuator/health/readiness)"
done
G=http://localhost:8080
code() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }
body() { curl -s -w '\n%{http_code}\n' "$@"; }
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
json=(-H 'Content-Type: application/json')
RUN=$(date +%s)
# Subjects of the mails that mention an order (newest first), and their recipients
mails() { curl -s "localhost:8025/api/v1/search?query=$1&limit=50" | grep -o '"Subject":"[^"]*"' | cut -d'"' -f4; }
rcpts() { curl -s "localhost:8025/api/v1/search?query=$1&limit=50" | grep -o '"To":\[{[^]]*\]' | grep -o '"Address":"[^"]*"' | cut -d'"' -f4 | sort | uniq -c; }
waitmails() { for i in $(seq 1 ${3:-30}); do [ "$(mails $1 | wc -l)" -ge "$2" ] && break; sleep 3; done; mails $1; }
```

Expected: `{"status":"UP"}` eight times (the last one is Notification on 8088).

## 2. Users, product and stock

```bash
login() { curl -s -X POST $G/auth/login "${json[@]}" -d '{"email":"'$1'","password":"'$2'"}' | field token; }
code -X POST $G/user/register "${json[@]}" -d '{"email":"alice'$RUN'@example.com","password":"secret123"}'
ALICE=$(login alice$RUN@example.com secret123); ADMIN=$(login admin@voltstore.com admin-local-password)
for r in manager:CATALOG_MANAGER worker:INVENTORY_WORKER; do
  code -X POST $G/user/non-customer "${json[@]}" -H "Authorization: Bearer $ADMIN" \
    -d '{"email":"'${r%%:*}$RUN'@example.com","password":"secret123","role":"'${r##*:}'"}'
done
MANAGER=$(login manager$RUN@example.com secret123); WORKER=$(login worker$RUN@example.com secret123)
AUTHA=(-H "Authorization: Bearer $ALICE"); AUTHM=(-H "Authorization: Bearer $MANAGER"); AUTHW=(-H "Authorization: Bearer $WORKER")
CATEGORY_ID=$(curl -s -X POST $G/catalog/categories "${json[@]}" "${AUTHM[@]}" -d '{"name":"Notification e2e '$RUN'"}' | field categoryId)
PRODUCT_ID=$(curl -s -X POST $G/catalog/products "${json[@]}" "${AUTHM[@]}" \
  -d '{"title":"Notification e2e laptop","description":"e2e","categoryId":"'$CATEGORY_ID'","basePrice":999.99}' | field productId)
sleep 3
code -X PUT $G/inventory/stock/$PRODUCT_ID/packaging "${json[@]}" "${AUTHW[@]}" \
  -d '{"grossWeightGrams":2100,"lengthCm":40,"widthCm":30,"heightCm":8}'
body -X POST $G/inventory/stock/$PRODUCT_ID/adjust "${json[@]}" "${AUTHW[@]}" -d '{"delta":10}'
code -X PATCH $G/catalog/products/$PRODUCT_ID/status "${json[@]}" "${AUTHM[@]}" -d '{"status":"ACTIVE"}'
sleep 3
ITEMS='[{"productId":"'$PRODUCT_ID'","quantity":1}]'
DELIVERY='{"name":"Alice Test","phone":"+380501234567","address":"Kyiv, Khreshchatyk 1","carrier":"NOVA_POSHTA"}'
card() { echo '{"cardDetails":{"number":"'$1'","expiryMonth":12,"expiryYear":2030,"cvv":"123"}}'; }
submit() { curl -s -X POST $G/orders "${json[@]}" "${AUTHA[@]}" -d '{"items":'"$ITEMS"',"deliveryDetails":'"$DELIVERY"'}' | field orderId; }
status() { curl -s $G/orders/$1 "${AUTHA[@]}" | field status; }
waitstatus() { for i in $(seq 1 30); do s=$(status $1); [ "$s" = "$2" ] && break; sleep 3; done; echo "$s"; }
```

Expected: `201`, `201`, `201`, `204`, `{"newAvailableQuantity":10}` `200`, `200`.

## 3. Journey A: happy path, four emails

```bash
A_ID=$(submit); echo "A_ID=$A_ID"; waitmails $A_ID 1
code -X POST $G/orders/$A_ID/pay "${json[@]}" "${AUTHA[@]}" -d "$(card 4242424242424242)"; waitmails $A_ID 2
waitstatus $A_ID PENDING_SHIPPING; code -X POST $G/orders/$A_ID/ship "${AUTHW[@]}"; waitmails $A_ID 3
waitstatus $A_ID DELIVERED; waitmails $A_ID 4; rcpts $A_ID
```

Expected, newest first after the last command:
```
VoltStore — Order #<A_ID> delivered
VoltStore — Order #<A_ID> has been shipped
VoltStore — Payment received for order #<A_ID>
VoltStore — Order #<A_ID> placed, awaiting payment
      4 alice<RUN>@example.com
```
Open one in the Mailpit UI: the "placed" mail gives the total (`999.99 UAH`) and the payment deadline in Kyiv time;
the "shipped" mail the TTN; the "delivered" mail the same TTN.

## 4. Journeys B and C: cancellation of a paid and an unpaid order

```bash
B_ID=$(submit); code -X POST $G/orders/$B_ID/pay "${json[@]}" "${AUTHA[@]}" -d "$(card 4242424242424242)"
waitstatus $B_ID PENDING_SHIPPING; code -X DELETE $G/orders/$B_ID "${AUTHA[@]}"; waitstatus $B_ID CANCELLED
C_ID=$(submit); waitmails $C_ID 1 >/dev/null; code -X DELETE $G/orders/$C_ID "${AUTHA[@]}"; waitstatus $C_ID CANCELLED
waitmails $B_ID 3; waitmails $C_ID 2
```

Expected: `200`, `PENDING_SHIPPING`, `202`, `CANCELLED`; `202`, `CANCELLED`. Order B: "cancelled", "payment
received", "placed". Order C: "cancelled", "placed". Both "cancelled" mails have the same text: "If the order was
already paid, the refund has been initiated..." (decision D3: `OrderCancelled` does not say whether a refund was
made, so the text promises nothing wrong for C).

## 5. Journey D: an unpaid order expires

```bash
D_ID=$(submit); waitstatus $D_ID EXPIRED; waitmails $D_ID 2
```

Expected: after the payment window plus grace (about 80 s) `EXPIRED`; the mails "expired, payment deadline
missed" and "placed". The "expired" mail says the items were released and nothing was charged.

## 6. Resilience: Notification down during a journey

```bash
docker stop notification-service
E_ID=$(submit); code -X POST $G/orders/$E_ID/pay "${json[@]}" "${AUTHA[@]}" -d "$(card 4242424242424242)"
waitstatus $E_ID PENDING_SHIPPING; code -X POST $G/orders/$E_ID/ship "${AUTHW[@]}"; waitstatus $E_ID DELIVERED
mails $E_ID | wc -l
docker start notification-service
waitmails $E_ID 4 40; rcpts $E_ID
sleep 40; mails $E_ID | wc -l
docker logs notification-service 2>&1 | grep -c "recipient_unknown"
```

Expected: `0` mails while Notification is down. After the start, all four mails arrive (the "delivered" one may
come up to 30 s later than the others: if Delivery's topic is read before Order's, the job finds no recipient yet,
records `recipient_unknown` and retries after its first back-off, D1). The recipient count is `4`, and still `4`
mails after waiting: none is sent twice. The last line shows whether the lag case happened in this run (0 or more).

## 7. No personal data in the logs

```bash
docker logs notification-service 2>&1 | grep -c "alice$RUN@"
```

Expected: `0`. Notification logs order, event and notification IDs only.
