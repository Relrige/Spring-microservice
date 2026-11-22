// S3 Checkout under a spike: logged-in customers check 1–3 products (EP-ORD-01) and place the order (EP-ORD-02)
// with delivery details and a fresh Idempotency-Key. The deepest path of the system: Gateway → Order → Catalog over
// HTTP (prices, status) and Inventory over gRPC (reservation), Order's database with the outbox.
// Profile: low rate, a sudden spike to more than ten times that, then back to the low rate, to see whether the
// system absorbs the spike and recovers.
import http from 'k6/http';
import { BASE_URL, TREND_STATS, expectStatus, loginCustomers, params, pick, pickDistinct, seededProducts, summaryFiles, uuid } from './lib/common.js';

const VALIDATE = 'POST /orders/validate';
const PLACE = 'POST /orders';
const SCALE = Number(__ENV.SCALE || 1);
const rate = (r) => Math.max(1, Math.round(r * SCALE));

const DELIVERY = [
    { name: 'Load Test Customer', phone: '+380501234567', address: 'Kyiv, Khreshchatyk 1', carrier: 'NOVA_POSHTA' },
    { name: 'Load Test Buyer', phone: '+380671112233', address: 'Lviv, Svobody Ave 5', carrier: 'NOVA_POSHTA' },
];

export const options = {
    scenarios: {
        checkout: {
            executor: 'ramping-arrival-rate',
            startRate: rate(2),
            timeUnit: '1s',
            preAllocatedVUs: 20,
            maxVUs: 200,
            stages: [
                { duration: '1m', target: rate(2) },
                { duration: '5s', target: rate(25) }, // spike
                { duration: '30s', target: rate(25) },
                { duration: '5s', target: rate(2) },
                { duration: '1m30s', target: rate(2) }, // recovery
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<1000', 'p(99)<2000'],
        http_req_failed: ['rate<0.02'],
        [`http_req_duration{name:${VALIDATE}}`]: ['p(95)<1000'],
        [`http_req_duration{name:${PLACE}}`]: ['p(95)<1000'],
        checks: ['rate>0.98'],
    },
    summaryTrendStats: TREND_STATS,
};

export function setup() {
    return { tokens: loginCustomers(), productIds: seededProducts().map((p) => p.productId) };
}

export default function iteration(data) {
    const token = pick(data.tokens);
    const items = pickDistinct(data.productIds, 1 + Math.floor(Math.random() * 3)) // NOSONAR(S2245): picks test data and traffic mix, not security-relevant
        .map((productId) => ({ productId, quantity: 1 }));

    const validate = http.post(`${BASE_URL}/orders/validate`, JSON.stringify({ items }), params(VALIDATE, token));
    if (!expectStatus(validate, VALIDATE, 204)) {
        return; // a shopper does not place an order the check refused
    }
    const order = http.post(`${BASE_URL}/orders`, JSON.stringify({ items, deliveryDetails: pick(DELIVERY) }),
        params(PLACE, token, { 'Idempotency-Key': uuid() }));
    expectStatus(order, PLACE, 201);
}

export const handleSummary = summaryFiles('s3-checkout');
