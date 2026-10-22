import { afterEach, describe, expect, it, vi } from 'vitest';
import { login, register } from '../api/auth';
import { getCart, getProfile, saveProfile } from '../api/customer';
import { ApiError } from '../api/errors';
import { configureHttp, request } from '../api/http';
import {
  cancelOrder,
  getOrder,
  listOrders,
  orderProblem,
  payOrder,
  placeOrder,
  validateCheckout,
  type DeliveryDetails,
} from '../api/order';
import { DEMO_PROFILE } from '../fixtures/demo-data';
import { db } from './db';
import { mockSettings } from './handlers';
import { createMockToken } from './tokens';

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('expected the request to fail');
}

const delivery: DeliveryDetails = {
  name: 'Olena Kovalenko',
  phone: '+380501234567',
  address: 'Kyiv, branch No. 12',
  carrier: 'NOVA_POSHTA',
};
const card = (number: string) => ({ number, expiryMonth: 12, expiryYear: 2099, cvv: '123' });
const inStock = () =>
  db.products.filter((p) => p.status === 'ACTIVE' && p.stockStatus === 'IN_STOCK');
const key = () => crypto.randomUUID();
const demoToken = () => createMockToken(db.users.find((u) => u.role === 'CUSTOMER')!);

async function newCustomer(email = 'buyer@example.com'): Promise<string> {
  await register({ email, password: 'secret' });
  return (await login({ email, password: 'secret' })).token;
}

/** Runs calls with `token` as the session token. */
async function as<T>(token: string, call: () => Promise<T>): Promise<T> {
  configureHttp({ getToken: () => token });
  try {
    return await call();
  } finally {
    configureHttp({ getToken: () => null });
  }
}

function order(token: string, productId: string, quantity = 1, k = key()) {
  return as(token, () =>
    placeOrder({ items: [{ productId, quantity }], deliveryDetails: delivery }, k),
  );
}

afterEach(() => {
  vi.useRealTimers();
  mockSettings.paymentDelayMs = 2_500;
});

describe('mock profile', () => {
  it('starts the demo customer from DEMO_PROFILE; a new customer gets every field null', async () => {
    expect(await as(demoToken(), () => getProfile())).toEqual(DEMO_PROFILE);
    const token = await newCustomer();
    expect(await as(token, () => getProfile())).toEqual({
      firstName: null,
      lastName: null,
      phone: null,
      defaultAddress: null,
    });
  });

  it('saves a valid profile and answers 400 with field errors', async () => {
    const token = await newCustomer();
    const saved = await as(token, () =>
      saveProfile({ firstName: 'A', lastName: 'B', phone: '+380501234567' }),
    );
    expect(saved).toMatchObject({ firstName: 'A', defaultAddress: null });
    const error = await failure(
      as(token, () => saveProfile({ firstName: ' ', lastName: 'B', phone: '0501' })),
    );
    expect(error.status).toBe(400);
    expect(Object.keys(error.problem?.errors ?? {}).sort()).toEqual(['firstName', 'phone']);
  });
});

describe('mock orders', () => {
  it('soft check: 204, or 422 ineligible items with the reasons', async () => {
    const token = await newCustomer();
    await as(token, () => validateCheckout([{ productId: inStock()[0]!.productId, quantity: 1 }]));
    const inactive = db.products.find((p) => p.status === 'NOT_ACTIVE')!;
    const soldOut = db.products.find(
      (p) => p.status === 'ACTIVE' && p.stockStatus === 'OUT_OF_STOCK',
    )!;
    const error = await failure(
      as(token, () =>
        validateCheckout([
          { productId: inactive.productId, quantity: 1 },
          { productId: soldOut.productId, quantity: 1 },
        ]),
      ),
    );
    expect(error.problem?.type).toBe('https://voltstore.com/errors/ineligible-items');
    expect(orderProblem(error)).toEqual({
      kind: 'ineligible-items',
      items: [
        { productId: inactive.productId, reason: 'NOT_ACTIVE' },
        { productId: soldOut.productId, reason: 'INSUFFICIENT_STOCK' },
      ],
    });
    expect((await failure(as(token, () => validateCheckout([])))).status).toBe(400);
  });

  it('places an order, reserves stock, replays the same key and rejects a mismatch', async () => {
    const token = await newCustomer();
    const product = inStock()[0]!;
    const before = product.available;
    const k = key();
    const placed = await order(token, product.productId, 2, k);
    expect(placed).toMatchObject({ status: 'PENDING_PAYMENT', totalAmount: product.basePrice * 2 });
    expect(product.available).toBe(before - 2);

    const replay = await order(token, product.productId, 2, k);
    expect(replay.orderId).toBe(placed.orderId);
    expect(db.orders).toHaveLength(1);
    expect(product.available).toBe(before - 2);

    const mismatch = await failure(order(token, product.productId, 1, k));
    expect(orderProblem(mismatch)).toEqual({ kind: 'idempotency-key-mismatch' });
    expect((await failure(order(token, product.productId, 1, 'not-a-uuid'))).status).toBe(400);
  });

  it('answers 422 for insufficient stock and an inactive product, 400 for a bad carrier', async () => {
    const token = await newCustomer();
    const product = inStock().find((p) => p.available < 10)!;
    const tooMany = await failure(order(token, product.productId, 10));
    expect(orderProblem(tooMany)).toEqual({
      kind: 'insufficient-stock',
      items: [{ productId: product.productId, requested: 10, available: product.available }],
    });
    const inactive = db.products.find((p) => p.status === 'NOT_ACTIVE')!;
    expect(orderProblem(await failure(order(token, inactive.productId)))?.kind).toBe(
      'ineligible-items',
    );
    const carrier = await failure(
      as(token, () =>
        placeOrder(
          {
            items: [{ productId: product.productId, quantity: 1 }],
            deliveryDetails: { ...delivery, carrier: 'UKRPOSHTA' },
          },
          key(),
        ),
      ),
    );
    expect(carrier.problem?.errors).toHaveProperty(['deliveryDetails.carrier']);
  });

  it('pays by test card: 0002 and others decline, 4242 and 5000 approve and clear the cart', async () => {
    mockSettings.paymentDelayMs = 0;
    const demo = demoToken();
    const cart = await as(demo, () => getCart());
    const line = cart.find(
      (l) => db.products.find((p) => p.productId === l.productId)!.status === 'ACTIVE',
    )!;
    const first = await order(demo, line.productId);
    const declined = await failure(
      as(demo, () => payOrder(first.orderId, card('4000000000000002'))),
    );
    expect(declined.status).toBe(402);
    expect(orderProblem(declined)).toEqual({
      kind: 'payment-declined',
      reason: 'insufficient_funds',
    });
    const other = await failure(as(demo, () => payOrder(first.orderId, card('4000000000001111'))));
    expect(orderProblem(other)).toEqual({ kind: 'payment-declined', reason: 'card_declined' });

    expect(await as(demo, () => payOrder(first.orderId, card('4242424242424242')))).toEqual({
      orderId: first.orderId,
      status: 'PAID',
    });
    expect((await as(demo, () => getCart())).map((l) => l.productId)).not.toContain(line.productId);
    const again = await failure(as(demo, () => payOrder(first.orderId, card('4242424242424242'))));
    expect(orderProblem(again)).toEqual({ kind: 'order-state', orderStatus: 'PAID' });

    const second = await order(demo, line.productId);
    const lost = await as(demo, () => payOrder(second.orderId, card('4000000000005000')));
    expect(lost.status).toBe('PAID');
    const third = await order(demo, line.productId);
    const expiredCard = await failure(
      as(demo, () => payOrder(third.orderId, { ...card('4242424242424242'), expiryYear: 2001 })),
    );
    expect(expiredCard.status).toBe(400);
  });

  it('expires an unpaid order lazily and refuses payment after the window', async () => {
    const token = await newCustomer();
    const product = inStock()[0]!;
    const before = product.available;
    const placed = await order(token, product.productId);
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.now() + 15 * 60_000 + 1_000);
    const late = await failure(as(token, () => payOrder(placed.orderId, card('4242424242424242'))));
    expect(orderProblem(late)).toEqual({ kind: 'payment-window-expired' });
    vi.setSystemTime(Date.now() + 60_000);
    expect((await as(token, () => getOrder(placed.orderId))).status).toBe('EXPIRED');
    expect(product.available).toBe(before);
  });

  it('lists newest first, shows a TTN 3 s after payment, and keeps orders private', async () => {
    const token = await newCustomer();
    const product = inStock()[0]!;
    const first = await order(token, product.productId);
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.now() + 1_000);
    const second = await order(token, product.productId);
    const page = await as(token, () => listOrders());
    expect(page.content.map((o) => o.orderId)).toEqual([second.orderId, first.orderId]);
    expect(page).toMatchObject({ page: 0, size: 20, totalElements: 2 });

    await as(token, () => payOrder(first.orderId, card('4242424242424242')));
    expect(await as(token, () => getOrder(first.orderId))).not.toHaveProperty('ttn');
    vi.setSystemTime(Date.now() + 3_000);
    const ready = await as(token, () => getOrder(first.orderId));
    expect(ready).toMatchObject({ status: 'PENDING_SHIPPING', deliveryStatus: 'TTN_GENERATED' });
    expect(ready.ttn).toMatch(/^2045\d{10}$/);

    const stranger = await newCustomer('other@example.com');
    expect((await failure(as(stranger, () => getOrder(first.orderId)))).status).toBe(404);
    expect((await failure(as(token, () => listOrders({ size: 101 })))).status).toBe(400);
  });

  it('cancels allowed statuses at once, releasing stock, and answers 409 otherwise', async () => {
    const token = await newCustomer();
    const product = inStock()[0]!;
    const before = product.available;
    const placed = await order(token, product.productId);
    expect(await as(token, () => cancelOrder(placed.orderId))).toEqual({
      orderId: placed.orderId,
      status: 'CANCELLED',
    });
    expect(product.available).toBe(before);
    const again = await failure(as(token, () => cancelOrder(placed.orderId)));
    expect(orderProblem(again)).toEqual({ kind: 'order-state', orderStatus: 'CANCELLED' });
  });

  it('answers 401 without a token and 403 for staff on the customer routes', async () => {
    const worker = { token: createMockToken(db.users.find((u) => u.role === 'INVENTORY_WORKER')!) };
    for (const path of ['/orders', '/customers/me/profile']) {
      expect((await failure(request(path, { auth: 'none' }))).status).toBe(401);
      expect((await failure(request(path, { auth: worker }))).status).toBe(403);
    }
  });
});
