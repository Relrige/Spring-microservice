// S1 Catalog browsing: anonymous shoppers list products (a category, a page or a search) and open one of them.
// Goal: find the rate at which the read path Gateway → Catalog (→ PostgreSQL) saturates.
// Open model (ramping-arrival-rate): new iterations start at the target rate whether or not earlier ones are done,
// so a slow server shows up as latency and dropped iterations instead of being hidden (coordinated omission).
import http from 'k6/http';
import { BASE_URL, MARKER, TREND_STATS, expectStatus, params, pick, seededProducts, summaryFiles } from './lib/common.js';

const LIST = 'GET /catalog/products';
const DETAILS = 'GET /catalog/products/{id}';
// Rate multiplier, so the same profile can be scaled to the machine (recorded in the report)
const SCALE = Number(__ENV.SCALE || 1);
const rate = (r) => Math.max(1, Math.round(r * SCALE));

export const options = {
    scenarios: {
        browse: {
            executor: 'ramping-arrival-rate',
            startRate: rate(10),
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 400,
            // Steps: short ramp, then a 30 s plateau at each rate
            stages: [
                { duration: '10s', target: rate(25) }, { duration: '30s', target: rate(25) },
                { duration: '10s', target: rate(50) }, { duration: '30s', target: rate(50) },
                { duration: '10s', target: rate(100) }, { duration: '30s', target: rate(100) },
                { duration: '10s', target: rate(150) }, { duration: '30s', target: rate(150) },
                { duration: '10s', target: rate(200) }, { duration: '30s', target: rate(200) },
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<300', 'p(99)<800'],
        http_req_failed: ['rate<0.01'],
        [`http_req_duration{name:${LIST}}`]: ['p(95)<300'],
        [`http_req_duration{name:${DETAILS}}`]: ['p(95)<300'],
        checks: ['rate>0.99'],
    },
    summaryTrendStats: TREND_STATS,
};

export function setup() {
    const products = seededProducts();
    return { products, categoryIds: [...new Set(products.map((p) => p.categoryId))] };
}

export default function iteration(data) {
    const roll = Math.random(); // NOSONAR(S2245): picks test data and traffic mix, not security-relevant
    let query;
    if (roll < 0.5) {
        query = `categoryId=${pick(data.categoryIds)}&size=20`;
    } else if (roll < 0.8) {
        query = `page=${Math.floor(Math.random() * 3)}&size=20&sortBy=basePrice,asc`; // NOSONAR(S2245): picks test data and traffic mix, not security-relevant
    } else {
        query = `search=${MARKER}%20product%20${Math.floor(Math.random() * 5)}&size=20`; // NOSONAR(S2245): picks test data and traffic mix, not security-relevant
    }
    const list = http.get(`${BASE_URL}/catalog/products?${query}`, params(LIST));
    expectStatus(list, LIST, 200);

    // Open one product from the page the shopper saw (a seeded one if the page was empty)
    let productId = pick(data.products).productId;
    if (list.status === 200) {
        const content = list.json('content');
        if (content && content.length > 0) {
            productId = pick(content).productId;
        }
    }
    const details = http.get(`${BASE_URL}/catalog/products/${productId}`, params(DETAILS));
    expectStatus(details, DETAILS, 200);
}

export const handleSummary = summaryFiles('s1-catalog-browse');
