// Seed data for the load scenarios, created only through the public API (through the API Gateway).
// Run once before the scenarios; running it again reuses what exists and tops the stock up.
//
// Creates: a catalog manager and an inventory worker (by the seeded administrator), 3 categories, 50 ACTIVE products
// with complete packaging and 1,000,000 units each, and CUSTOMER_COUNT customers load-user-{n}@voltstore.test.
//
// Variables (-e): ADMIN_EMAIL, ADMIN_PASSWORD (from auth-service/.env), STAFF_PASSWORD, CUSTOMER_PASSWORD,
// optionally BASE_URL, CUSTOMER_COUNT, PRODUCT_COUNT.
import http from 'k6/http';
import { fail } from 'k6';
import {
    BASE_URL, CUSTOMER_COUNT, MARKER, customerEmail, login, params, requireEnv,
} from './lib/common.js';

export const options = {
    vus: 1,
    iterations: 1,
    // The seed is not a measurement
    thresholds: {},
    summaryTrendStats: ['avg', 'max'],
};

const PRODUCT_COUNT = Number(__ENV.PRODUCT_COUNT || 50);
const TARGET_STOCK = 1000000;
const CATEGORIES = [`${MARKER} laptops`, `${MARKER} phones`, `${MARKER} accessories`];
const MANAGER_EMAIL = 'load-manager@voltstore.test';
const WORKER_EMAIL = 'load-worker@voltstore.test';

function expect(res, what, ...statuses) {
    if (!statuses.includes(res.status)) {
        fail(`${what}: expected ${statuses.join('/')}, got ${res.status} ${res.body}`);
    }
    return res;
}

function ensureStaff(adminToken, email, role, password) {
    const res = http.post(`${BASE_URL}/user/non-customer`, JSON.stringify({ email, password, role }),
        params('seed: create staff', adminToken));
    expect(res, `create ${role}`, 201, 409);
    return login(email, password);
}

function ensureCategories(manager) {
    const res = expect(http.get(`${BASE_URL}/catalog/categories?search=${MARKER}&size=100`,
        params('seed: list categories', manager)), 'list categories', 200);
    const existing = Object.fromEntries(res.json('content').map((c) => [c.name, c.categoryId]));
    return CATEGORIES.map((name) => {
        if (existing[name]) {
            return existing[name];
        }
        return expect(http.post(`${BASE_URL}/catalog/categories`, JSON.stringify({ name }),
            params('seed: create category', manager)), `create category ${name}`, 201).json('categoryId');
    });
}

function ensureProducts(manager, categoryIds) {
    // Staff see every status, so products from an earlier, interrupted seed are found too
    const res = expect(http.get(`${BASE_URL}/catalog/products?search=${MARKER}&size=100`,
        params('seed: list products', manager)), 'list products', 200);
    const existing = Object.fromEntries(res.json('content').map((p) => [p.title, p]));
    const products = [];
    for (let n = 1; n <= PRODUCT_COUNT; n++) {
        const title = `${MARKER} product ${String(n).padStart(2, '0')}`;
        if (existing[title]) {
            products.push({ productId: existing[title].productId, status: existing[title].status });
            continue;
        }
        const body = {
            title,
            description: `Seeded for the k6 load scenarios (${n})`,
            categoryId: categoryIds[n % categoryIds.length],
            basePrice: 100 + n * 10.5,
        };
        const created = expect(http.post(`${BASE_URL}/catalog/products`, JSON.stringify(body),
            params('seed: create product', manager)), `create ${title}`, 201);
        products.push({ productId: created.json('productId'), status: 'NOT_ACTIVE' });
    }
    return products;
}

function ensureStockAndPackaging(worker, productId) {
    expect(http.put(`${BASE_URL}/inventory/stock/${productId}/packaging`,
        JSON.stringify({ grossWeightGrams: 1500, lengthCm: 35, widthCm: 25, heightCm: 8 }),
        params('seed: set packaging', worker)), `packaging of ${productId}`, 204);
    const item = expect(http.get(`${BASE_URL}/inventory/stock/${productId}`, params('seed: get stock', worker)),
        `stock of ${productId}`, 200);
    const delta = TARGET_STOCK - item.json('availableQuantity');
    if (delta > 0) {
        expect(http.post(`${BASE_URL}/inventory/stock/${productId}/adjust`, JSON.stringify({ delta }),
            params('seed: adjust stock', worker)), `adjust ${productId}`, 200);
    }
}

function ensureCustomers(password) {
    let created = 0;
    for (let n = 1; n <= CUSTOMER_COUNT; n++) {
        const res = http.post(`${BASE_URL}/user/register`, JSON.stringify({ email: customerEmail(n), password }),
            params('seed: register customer'));
        expect(res, `register ${customerEmail(n)}`, 201, 409);
        if (res.status === 201) {
            created++;
        }
    }
    return created;
}

export default function seed() {
    const staffPassword = requireEnv('STAFF_PASSWORD');
    const admin = login(requireEnv('ADMIN_EMAIL'), requireEnv('ADMIN_PASSWORD'));
    const manager = ensureStaff(admin, MANAGER_EMAIL, 'CATALOG_MANAGER', staffPassword);
    const worker = ensureStaff(admin, WORKER_EMAIL, 'INVENTORY_WORKER', staffPassword);

    const categoryIds = ensureCategories(manager);
    const products = ensureProducts(manager, categoryIds);
    let activated = 0;
    for (const product of products) {
        ensureStockAndPackaging(worker, product.productId);
        if (product.status !== 'ACTIVE') {
            expect(http.patch(`${BASE_URL}/catalog/products/${product.productId}/status`,
                JSON.stringify({ status: 'ACTIVE' }), params('seed: activate product', manager)),
            `activate ${product.productId}`, 200);
            activated++;
        }
    }
    const customers = ensureCustomers(requireEnv('CUSTOMER_PASSWORD'));

    console.log(`seed done: ${categoryIds.length} categories, ${products.length} products (${activated} activated now), `
        + `stock topped up to ${TARGET_STOCK}, ${CUSTOMER_COUNT} customers (${customers} registered now)`);
}
