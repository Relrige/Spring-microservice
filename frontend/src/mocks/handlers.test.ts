import { beforeEach, describe, expect, it } from 'vitest';
import { login, register } from '../api/auth';
import { getProduct, listCategories, listProducts } from '../api/catalog';
import { ApiError } from '../api/errors';
import { request } from '../api/http';
import { DEMO_CATEGORIES, DEMO_CUSTOMER, DEMO_PRODUCTS, DEMO_STAFF } from '../fixtures/demo-data';
import { db, resetDb } from './db';
import { compareText } from './handlers';
import { createMockToken, readMockToken } from './tokens';

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('expected the request to fail');
}

beforeEach(() => resetDb());

describe('mock auth', () => {
  it('registers a customer and rejects a duplicate email in any case', async () => {
    const created = await register({ email: 'new@example.com', password: 'secret' });
    expect(created.userId).toMatch(/[0-9a-f-]{36}/);
    const duplicate = await failure(register({ email: 'NEW@example.com', password: 'secret' }));
    expect(duplicate.status).toBe(409);
    expect(duplicate.problem?.detail).toBe('Email already in use');
  });

  it('answers 400 with field errors', async () => {
    const error = await failure(register({ email: 'nope', password: '123' }));
    expect(error.status).toBe(400);
    expect(error.problem?.errors).toEqual({
      email: ['must be a well-formed email address'],
      password: ['size must be between 5 and 64'],
    });
  });

  it('logs in every demo account and rejects a wrong password with 401', async () => {
    for (const account of [DEMO_CUSTOMER, ...DEMO_STAFF]) {
      const { token } = await login(account);
      expect(readMockToken(token)).toMatchObject({ role: account.role, email: account.email });
    }
    const error = await failure(login({ email: DEMO_CUSTOMER.email, password: 'wrong-pass' }));
    expect(error.status).toBe(401);
    expect(error.problem?.detail).toBe('Invalid credentials');
  });
});

describe('mock catalog', () => {
  it('starts from the fixtures: 4 categories, ordered by name', async () => {
    const categories = await listCategories();
    expect(categories.content.map((c) => c.name)).toEqual([...DEMO_CATEGORIES].sort());
  });

  it('hides inactive products and lists out-of-stock ones', async () => {
    const page = await listProducts({ size: 100 });
    const active = DEMO_PRODUCTS.filter((p) => p.active);
    expect(page.totalElements).toBe(active.length);
    const titles = page.content.map((p) => p.title);
    for (const inactive of DEMO_PRODUCTS.filter((p) => !p.active)) {
      expect(titles).not.toContain(inactive.title);
    }
    const outOfStock = page.content.filter((p) => p.stockStatus === 'OUT_OF_STOCK');
    expect(outOfStock.length).toBe(active.filter((p) => p.targetStock === 0).length);
    expect(outOfStock.length).toBeGreaterThan(0);
  });

  it('pages 20 at a time ordered by title', async () => {
    const first = await listProducts({});
    expect(first.content).toHaveLength(20);
    const titles = first.content.map((p) => p.title);
    expect(titles).toEqual([...titles].sort(compareText));
  });

  it('filters by search, category and price, and sorts by price', async () => {
    const phones = db.categories.find((c) => c.name === 'Phones')!;
    const page = await listProducts({
      categoryId: phones.categoryId,
      minPrice: 15000,
      maxPrice: 40000,
      sortBy: 'basePrice,desc',
      size: 100,
    });
    const prices = page.content.map((p) => p.basePrice);
    expect(prices.every((p) => p >= 15000 && p <= 40000)).toBe(true);
    expect(prices).toEqual([...prices].sort((a, b) => b - a));
    expect(page.content.every((p) => p.categoryId === phones.categoryId)).toBe(true);
    const search = await listProducts({ search: 'macbook' });
    expect(search.content.every((p) => p.title.includes('MacBook'))).toBe(true);
  });

  it('answers 400 when minPrice > maxPrice', async () => {
    const error = await failure(listProducts({ minPrice: 10, maxPrice: 5 }));
    expect(error.status).toBe(400);
  });

  it('shows all products to a manager token and only active ones without it', async () => {
    const manager = db.users.find((u) => u.role === 'CATALOG_MANAGER')!;
    const token = createMockToken(manager);
    const all = await listProducts({ size: 100 }, { auth: { token } });
    expect(all.totalElements).toBe(DEMO_PRODUCTS.length);
  });

  it('answers 404 for inactive, unknown and non-UUID product ids', async () => {
    const inactive = db.products.find((p) => p.status === 'NOT_ACTIVE')!;
    const active = db.products.find((p) => p.status === 'ACTIVE')!;
    expect((await getProduct(active.productId)).title).toBe(active.title);
    for (const id of [inactive.productId, '00000000-0000-4000-8000-ffffffffffff', 'not-a-uuid']) {
      expect((await failure(getProduct(id))).status).toBe(404);
    }
  });

  it('answers 401 on an Optional route for an invalid token and 404 for unknown routes', async () => {
    const error = await failure(listProducts({}, { auth: { token: 'garbage' } }));
    expect(error.status).toBe(401);
    expect((await failure(request('/no/such/route'))).status).toBe(404);
  });
});
