// S2 Customer cart: logged-in customers add a product, change its quantity, read the cart and sometimes remove a
// product. Write path Gateway (JWT check) → Customer (→ PostgreSQL) at a steady rate.
// Each VU uses its own customer (by __VU), so no two concurrent iterations edit the same cart.
import http from 'k6/http';
import { BASE_URL, CUSTOMER_COUNT, TREND_STATS, expectStatus, loginCustomers, params, pick, seededProducts, summaryFiles } from './lib/common.js';

const ADD = 'POST /customers/cart/items';
const SET = 'PATCH /customers/cart/items/{id}';
const GET = 'GET /customers/cart';
const REMOVE = 'DELETE /customers/cart/items/{id}';
const SCALE = Number(__ENV.SCALE || 1);

export const options = {
    scenarios: {
        cart: {
            executor: 'constant-arrival-rate',
            rate: Math.max(1, Math.round(30 * SCALE)),
            timeUnit: '1s',
            duration: __ENV.DURATION || '3m',
            preAllocatedVUs: 20,
            // At most one VU per customer
            maxVUs: CUSTOMER_COUNT,
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<400', 'p(99)<1000'],
        http_req_failed: ['rate<0.01'],
        [`http_req_duration{name:${ADD}}`]: ['p(95)<400'],
        [`http_req_duration{name:${SET}}`]: ['p(95)<400'],
        [`http_req_duration{name:${GET}}`]: ['p(95)<400'],
        checks: ['rate>0.99'],
    },
    summaryTrendStats: TREND_STATS,
};

export function setup() {
    const tokens = loginCustomers();
    // Every run starts with empty carts, so the 10-product cart limit is never hit by leftovers
    for (const token of tokens) {
        http.del(`${BASE_URL}/customers/cart`, null, params('setup: clear cart', token));
    }
    return { tokens, productIds: seededProducts().map((p) => p.productId) };
}

export default function (data) {
    const token = data.tokens[(__VU - 1) % data.tokens.length];
    const productId = pick(data.productIds);
    const item = `${BASE_URL}/customers/cart/items/${productId}`;

    expectStatus(http.post(`${BASE_URL}/customers/cart/items`, JSON.stringify({ productId, quantity: 1 }),
        params(ADD, token)), ADD, 200);
    // An explicit quantity (1–3) keeps every product under the per-product limit of 10
    expectStatus(http.patch(item, JSON.stringify({ quantity: 1 + Math.floor(Math.random() * 3) }),
        params(SET, token)), SET, 200);
    const cart = http.get(`${BASE_URL}/customers/cart`, params(GET, token));
    expectStatus(cart, GET, 200);

    // Remove sometimes, and always once the cart nears its limit of 10 products
    const size = cart.status === 200 ? cart.json().length : 0;
    if (size >= 8 || Math.random() < 0.3) {
        expectStatus(http.del(item, null, params(REMOVE, token)), REMOVE, 200);
    }
}

export const handleSummary = summaryFiles('s2-cart');
