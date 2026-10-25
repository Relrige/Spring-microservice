#!/bin/sh
# End-to-end scenario of the tracing report (docs/tasks/infrastructure/observability/07): the demo customer places an
# order and pays it through the API Gateway, the same two requests the frontend's checkout makes. Each request is
# sent with its own W3C traceparent, so its trace id is known up front. Successful traces are kept at 25 % by the
# collector's tail sampler, so the run repeats until Tempo has the pay trace (at most TRIES times).
#
# Needs the seed data (frontend: npm run seed), node, and two port-forwards:
#   kubectl port-forward -n voltstore svc/api-gateway 18081:8080
#   kubectl port-forward -n voltstore svc/tempo 13200:3200
# Usage: sh e2e-trace.sh            (env: GATEWAY, TEMPO, TRIES)
set -e
GATEWAY=${GATEWAY:-http://localhost:18081}
TEMPO=${TEMPO:-http://localhost:13200}
TRIES=${TRIES:-10}

json() { node -pe "JSON.parse(require('fs').readFileSync(0)).$1"; }
hex() { node -pe "require('crypto').randomBytes($1).toString('hex')"; }

TOKEN=$(curl -sf -X POST "$GATEWAY/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"customer@voltstore.com","password":"customer-demo"}' | json token)

try=1
while [ "$try" -le "$TRIES" ]; do
  PRODUCT=$(curl -sf "$GATEWAY/catalog/products?size=50" \
    | json 'content.find(p => p.stockStatus === "IN_STOCK").productId')
  PLACE_TRACE=$(hex 16)
  ORDER=$(curl -sf -X POST "$GATEWAY/orders" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(node -pe 'require("crypto").randomUUID()')" -H "traceparent: 00-$PLACE_TRACE-$(hex 8)-01" \
    -d "{\"items\":[{\"productId\":\"$PRODUCT\",\"quantity\":1}],\"deliveryDetails\":{\"name\":\"Trace Demo\",\
\"phone\":\"+380501234567\",\"address\":\"Kyiv, Khreshchatyk 1\",\"carrier\":\"NOVA_POSHTA\"}}" | json orderId)
  PAY_TRACE=$(hex 16)
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/orders/$ORDER/pay" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "traceparent: 00-$PAY_TRACE-$(hex 8)-01" \
    -d '{"cardDetails":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2030,"cvv":"123"}}')
  echo "run $try: order $ORDER, pay $STATUS, place trace $PLACE_TRACE, pay trace $PAY_TRACE"
  # decision_wait (10 s) + batching + Tempo ingestion
  sleep 16
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "$TEMPO/api/traces/$PAY_TRACE")" = 200 ]; then
    echo "kept by the tail sampler after $try run(s): pay trace $PAY_TRACE"
    exit 0
  fi
  try=$((try + 1))
done
echo "no pay trace kept after $TRIES runs (25 % baseline); run again" >&2
exit 1
