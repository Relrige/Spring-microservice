import { beforeEach, describe, expect, it } from 'vitest';
import { login, register } from '../api/auth';
import { getProduct, listCategories, listProducts } from '../api/catalog';
import {
  addCartItem,
  cartViolation,
  clearCart,
  getCart,
  mergeCart,
  removeCartItem,
  replaceCart,
  setCartItemQuantity,
} from '../api/customer';
import { ApiError } from '../api/errors';
import { request } from '../api/http';
import {
  DEMO_CART,
  DEMO_CATEGORIES,
  DEMO_CUSTOMER,
  DEMO_PRODUCTS,
  DEMO_STAFF,
} from '../fixtures/demo-data';
import { db, resetDb } from './db';
import { compareText, problem } from './handlers';
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

  it('shows every status to an inventory worker and filters by ids (Catalog tasks 27–28)', async () => {
    const worker = { token: createMockToken(db.users.find((u) => u.role === 'INVENTORY_WORKER')!) };
    expect((await listProducts({ size: 100 }, { auth: worker })).totalElements).toBe(
      DEMO_PRODUCTS.length,
    );
    const inactive = db.products.find((p) => p.status === 'NOT_ACTIVE')!;
    const active = db.products.find((p) => p.status === 'ACTIVE')!;
    const ids = [inactive.productId, active.productId];
    const page = await listProducts({ ids, size: 100 }, { auth: worker });
    expect(page.content.map((p) => p.productId).sort()).toEqual([...ids].sort());
    expect((await listProducts({ ids })).content.map((p) => p.productId)).toEqual([
      active.productId,
    ]);
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

describe('mock problems', () => {
  it('writes the backend type URIs: validation for a 400, the given kind when one is passed', async () => {
    const invalid = await failure(register({ email: 'not-an-email', password: 'secret' }));
    expect(invalid.problem?.type).toBe('https://voltstore.com/errors/validation');

    const custom = await problem(
      422,
      'Too many products',
      { violation: 'TOO_MANY_PRODUCTS' },
      'customer-service',
      'cart-rule-violation',
    ).json();
    expect(custom).toMatchObject({
      type: 'https://voltstore.com/errors/cart-rule-violation',
      status: 422,
      service: 'customer-service',
      violation: 'TOO_MANY_PRODUCTS',
    });
    const plain = await problem(422, 'Not processable').json();
    expect(plain.type).toBe('https://voltstore.com/errors/unprocessable-entity');
  });
});

describe('mock cart', () => {
  const customer = () => ({ token: createMockToken(db.users.find((u) => u.role === 'CUSTOMER')!) });
  const productId = (title: string) => db.products.find((p) => p.title === title)!.productId;
  const active = () => db.products.filter((p) => p.status === 'ACTIVE');

  async function emptyCustomer() {
    await register({ email: 'cart@example.com', password: 'secret' });
    return { token: (await login({ email: 'cart@example.com', password: 'secret' })).token };
  }

  it('starts the demo customer from DEMO_CART: three lines ordered by productId', async () => {
    const cart = await getCart(customer());
    expect(cart).toEqual(
      DEMO_CART.map((line) => ({ productId: productId(line.title), quantity: line.quantity })).sort(
        (a, b) => compareText(a.productId, b.productId),
      ),
    );
    expect(cart).toHaveLength(3);
  });

  it('adds, sets, removes and clears; a new customer starts with []', async () => {
    const auth = await emptyCustomer();
    expect(await getCart(auth)).toEqual([]);
    const [a, b] = active();
    await addCartItem({ productId: b!.productId, quantity: 1 }, auth);
    expect(await addCartItem({ productId: a!.productId, quantity: 2 }, auth)).toEqual(
      [
        { productId: a!.productId, quantity: 2 },
        { productId: b!.productId, quantity: 1 },
      ].sort((x, y) => compareText(x.productId, y.productId)),
    );
    expect(await addCartItem({ productId: a!.productId, quantity: 3 }, auth)).toContainEqual({
      productId: a!.productId,
      quantity: 5,
    });
    expect(await setCartItemQuantity(a!.productId, 1, auth)).toContainEqual({
      productId: a!.productId,
      quantity: 1,
    });
    expect(await removeCartItem(a!.productId, auth)).toEqual([
      { productId: b!.productId, quantity: 1 },
    ]);
    expect(await removeCartItem(a!.productId, auth)).toHaveLength(1);
    expect(await clearCart(auth)).toEqual([]);
    expect(await clearCart(auth)).toEqual([]);
  });

  it('answers 422 cart-rule-violation with the violation and leaves the cart unchanged', async () => {
    const auth = await emptyCustomer();
    const ten = active()
      .slice(0, 10)
      .map((p) => ({ productId: p.productId, quantity: 1 }));
    await replaceCart({ items: ten }, auth);
    const tooMany = await failure(
      addCartItem({ productId: active()[10]!.productId, quantity: 1 }, auth),
    );
    expect(tooMany.status).toBe(422);
    expect(tooMany.problem?.type).toBe('https://voltstore.com/errors/cart-rule-violation');
    expect(cartViolation(tooMany)).toMatchObject({
      violation: 'TOO_MANY_PRODUCTS',
      resultingProductCount: 11,
      maxProducts: 10,
    });

    const first = ten[0]!.productId;
    const over = await failure(addCartItem({ productId: first, quantity: 10 }, auth));
    expect(cartViolation(over)).toMatchObject({
      violation: 'QUANTITY_LIMIT_EXCEEDED',
      productIds: [first],
      maxQuantity: 10,
    });
    expect(cartViolation(await failure(setCartItemQuantity(first, 11, auth)))?.violation).toBe(
      'QUANTITY_LIMIT_EXCEEDED',
    );
    expect(
      cartViolation(await failure(mergeCart({ items: [{ productId: first, quantity: 10 }] }, auth)))
        ?.violation,
    ).toBe('QUANTITY_LIMIT_EXCEEDED');
    expect(await getCart(auth)).toHaveLength(10);
  });

  it('checks DUPLICATE_PRODUCT before the rules and merges by summing', async () => {
    const auth = await emptyCustomer();
    const [a, b] = active();
    const twice = [
      { productId: a!.productId, quantity: 9 },
      { productId: a!.productId, quantity: 9 },
    ];
    for (const call of [replaceCart, mergeCart]) {
      const error = await failure(call({ items: twice }, auth));
      expect(cartViolation(error)).toMatchObject({
        violation: 'DUPLICATE_PRODUCT',
        productIds: [a!.productId],
      });
    }
    await addCartItem({ productId: a!.productId, quantity: 1 }, auth);
    expect(await mergeCart({ items: [] }, auth)).toEqual([
      { productId: a!.productId, quantity: 1 },
    ]);
    const merged = await mergeCart(
      {
        items: [
          { productId: a!.productId, quantity: 2 },
          { productId: b!.productId, quantity: 1 },
        ],
      },
      auth,
    );
    expect(merged).toContainEqual({ productId: a!.productId, quantity: 3 });
    expect(merged).toContainEqual({ productId: b!.productId, quantity: 1 });
  });

  it('answers 404 only for PATCH of a missing line, and 400 with field errors', async () => {
    const auth = await emptyCustomer();
    const missing = await failure(setCartItemQuantity(active()[0]!.productId, 2, auth));
    expect(missing.status).toBe(404);
    const bad = await failure(addCartItem({ productId: 'nope', quantity: 1.5 }, auth));
    expect(bad.status).toBe(400);
    expect(bad.problem?.errors).toEqual({
      productId: ['must be a valid UUID'],
      quantity: ['must be an integer'],
    });
    const zero = await failure(
      replaceCart({ items: [{ productId: active()[0]!.productId, quantity: 0 }] }, auth),
    );
    expect(zero.problem?.errors).toEqual({
      'items[0].quantity': ['must be greater than or equal to 1'],
    });
  });

  it('answers 401 without a token and 403 for a staff token', async () => {
    const worker = { token: createMockToken(db.users.find((u) => u.role === 'INVENTORY_WORKER')!) };
    expect((await failure(getCart('none'))).status).toBe(401);
    expect((await failure(getCart(worker))).status).toBe(403);
    expect((await failure(clearCart(worker))).status).toBe(403);
  });
});
