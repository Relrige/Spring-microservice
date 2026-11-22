// Shared helpers for the seed script and the three load scenarios.
// Secrets (passwords) come only from k6 -e variables; nothing secret is written in these files.
import http from 'k6/http';
import { check, fail } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

export const BASE_URL = (__ENV.BASE_URL || 'http://host.docker.internal:8080').replace(/\/$/, '');
export const CUSTOMER_COUNT = Number(__ENV.CUSTOMER_COUNT || 50);

// Every seeded catalog entry carries this marker, so the scenarios find exactly the seeded data
export const MARKER = 'LoadTest';

export const TREND_STATS = ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'];

export function requireEnv(name) {
    const value = __ENV[name];
    if (!value) {
        fail(`missing k6 variable: pass -e ${name}=...`);
    }
    return value;
}

export function customerEmail(n) {
    return `load-user-${n}@voltstore.test`;
}

/** Request params: JSON body, optional bearer token, a `name` tag so the summary reports each step. */
export function params(name, token, extraHeaders) {
    const headers = { 'Content-Type': 'application/json', ...extraHeaders };
    if (token) {
        headers.Authorization = `Bearer ${token}`;
    }
    return { headers, tags: { name } };
}

/** One check per request: the expected status, named after the step. */
export function expectStatus(res, name, ...statuses) {
    return check(res, { [`${name} → ${statuses.join('/')}`]: (r) => statuses.includes(r.status) });
}

export function login(email, password) {
    const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ email, password }),
        params('POST /auth/login'));
    if (res.status !== 200) {
        fail(`login of ${email} failed with ${res.status}`);
    }
    return res.json('token');
}

/** Tokens of all seeded customers, index 0 = load-user-1. Done in setup(), outside the measured load. */
export function loginCustomers() {
    const password = requireEnv('CUSTOMER_PASSWORD');
    const tokens = [];
    for (let n = 1; n <= CUSTOMER_COUNT; n++) {
        tokens.push(login(customerEmail(n), password));
    }
    return tokens;
}

/** Ids of the seeded ACTIVE products, read anonymously like a shopper would. */
export function seededProducts() {
    const res = http.get(`${BASE_URL}/catalog/products?search=${MARKER}&size=100`, params('setup: list products'));
    if (res.status !== 200) {
        fail(`product list failed with ${res.status}`);
    }
    const products = res.json('content').map((p) => ({ productId: p.productId, categoryId: p.categoryId }));
    if (products.length === 0) {
        fail('no seeded products found: run seed.js first');
    }
    return products;
}

export function pick(array) {
    return array[Math.floor(Math.random() * array.length)];
}

/** Distinct random elements. */
export function pickDistinct(array, count) {
    const copy = array.slice();
    const result = [];
    while (result.length < count && copy.length > 0) {
        result.push(copy.splice(Math.floor(Math.random() * copy.length), 1)[0]);
    }
    return result;
}

export function uuid() {
    // RFC 4122 version 4 from Math.random: enough for idempotency keys in a load test
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = Math.floor(Math.random() * 16);
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}

/**
 * handleSummary for a scenario: the full summary as JSON and the text summary in OUT_DIR (default /out, the report
 * folder mounted by the run command), and the text summary on stdout. setup()'s return value (the customers' JWTs)
 * is removed first, so no token ends up in the saved results.
 */
export function summaryFiles(scenario) {
    return (data) => {
        delete data.setup_data;
        const outDir = __ENV.OUT_DIR || '/out';
        const label = __ENV.RUN_LABEL ? `-${__ENV.RUN_LABEL}` : '';
        const text = textSummary(data, { indent: ' ', enableColors: false });
        return {
            stdout: text,
            [`${outDir}/${scenario}${label}-summary.json`]: JSON.stringify(data, null, 2),
            [`${outDir}/${scenario}${label}-summary.txt`]: text,
        };
    };
}
