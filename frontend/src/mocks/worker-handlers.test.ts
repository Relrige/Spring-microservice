import { afterEach, describe, expect, it, vi } from 'vitest';
import { retryTtn, ttnFailure } from '../api/delivery';
import { ApiError } from '../api/errors';
import { configureHttp, request } from '../api/http';
import { adjustStock, listStock, setPackaging } from '../api/inventory';
import { getFulfillmentQueue, getOrder, orderProblem, shipOrder } from '../api/order';
import { addOrder, ORDER_PRODUCT } from '../test/orders';
import { db } from './db';
import { createMockToken } from './tokens';

const token = (role: 'INVENTORY_WORKER' | 'CUSTOMER') =>
  createMockToken(db.users.find((u) => u.role === role)!);
const worker = () => ({ token: token('INVENTORY_WORKER') });
const product = () => db.products.find((p) => p.title === ORDER_PRODUCT)!;
const paidAgo = (ms: number) => ({
  status: 'PAID' as const,
  paidAt: new Date(Date.now() - ms).toISOString(),
});

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('expected the request to fail');
}

/** Runs calls with the worker's token as the session token. */
async function asWorker<T>(call: () => Promise<T>): Promise<T> {
  configureHttp({ getToken: () => token('INVENTORY_WORKER') });
  try {
    return await call();
  } finally {
    configureHttp({ getToken: () => null });
  }
}

afterEach(() => vi.useRealTimers());

describe('mock stock', () => {
  it('lists rows with copied title and status, reserved from open orders', async () => {
    addOrder({ items: [{ ...addOrder().items[0]!, quantity: 2 }] });
    const page = await listStock({ search: 'sony wh', size: 100 }, worker());
    expect(page.content).toEqual([
      {
        productId: product().productId,
        title: ORDER_PRODUCT,
        productStatus: 'ACTIVE',
        availableQuantity: product().available,
        reservedQuantity: 3,
        totalQuantity: product().available + 3,
        packagingConfigured: true,
      },
    ]);
  });

  it('filters by packaging and status, sorts, and pages', async () => {
    product().packaging = null;
    const missing = await listStock({ packagingConfigured: false, size: 100 }, worker());
    expect(missing.content.map((r) => r.title)).toEqual([ORDER_PRODUCT]);
    const inactive = await listStock({ productStatus: 'NOT_ACTIVE', size: 100 }, worker());
    expect(inactive.content.every((r) => r.productStatus === 'NOT_ACTIVE')).toBe(true);
    expect(inactive.totalElements).toBe(
      db.products.filter((p) => p.status === 'NOT_ACTIVE').length,
    );
    const byAvailable = await listStock({ sortBy: 'availableQuantity,asc', size: 100 }, worker());
    const available = byAvailable.content.map((r) => r.availableQuantity);
    expect(available).toEqual([...available].sort((a, b) => a - b));
    const second = await listStock({ page: 1, size: 20 }, worker());
    expect(second).toMatchObject({ page: 1, size: 20, totalElements: db.products.length });
    expect((await failure(listStock({ sortBy: 'price' }, worker()))).status).toBe(400);
  });

  it('adjusts stock: 422 below zero, 400 for zero, 404 for an unknown product', async () => {
    const before = product().available;
    expect(await adjustStock(product().productId, 5, worker())).toEqual({
      newAvailableQuantity: before + 5,
    });
    const tooMuch = await failure(adjustStock(product().productId, -(before + 6), worker()));
    expect(tooMuch.status).toBe(422);
    expect(product().available).toBe(before + 5);
    expect((await failure(adjustStock(product().productId, 0, worker()))).status).toBe(400);
    const unknown = '00000000-0000-4000-8000-ffffffffffff';
    expect((await failure(adjustStock(unknown, 1, worker()))).status).toBe(404);
  });

  it('replaces packaging: 204, or 400 with field errors', async () => {
    product().packaging = null;
    const values = { grossWeightGrams: 350, lengthCm: 26, widthCm: 21, heightCm: 10 };
    await setPackaging(product().productId, values, worker());
    expect(product().packaging).toEqual(values);
    const bad = await failure(
      setPackaging(product().productId, { ...values, heightCm: 0 }, worker()),
    );
    expect(Object.keys(bad.problem?.errors ?? {})).toEqual(['heightCm']);
  });

  it('answers 401 without a token and 403 for a customer', async () => {
    expect((await failure(request('/inventory/stock', { auth: 'none' }))).status).toBe(401);
    const customer = { token: token('CUSTOMER') };
    expect((await failure(listStock({}, customer))).status).toBe(403);
    expect((await failure(request('/orders/fulfillment-queue', { auth: customer }))).status).toBe(
      403,
    );
  });
});

describe('mock fulfillment', () => {
  it('queues paid orders oldest first and ships one with a generated TTN', async () => {
    const newer = addOrder(paidAgo(4_000));
    const older = addOrder(paidAgo(60_000));
    addOrder();
    const queue = await asWorker(() => getFulfillmentQueue());
    expect(queue.deliveryDataAvailable).toBe(true);
    expect(queue.orders.map((o) => o.orderId)).toEqual([older.orderId, newer.orderId]);
    expect(queue.orders[0]).toMatchObject({
      deliveryStatus: 'TTN_GENERATED',
      status: 'PENDING_SHIPPING',
    });

    const shipped = await asWorker(() => shipOrder(older.orderId));
    expect(shipped).toMatchObject({ orderId: older.orderId, status: 'SHIPPED' });
    expect(shipped.ttn).toMatch(/^2045\d{10}$/);
    const again = await failure(asWorker(() => shipOrder(older.orderId)));
    expect(orderProblem(again)).toEqual({ kind: 'order-state', orderStatus: 'SHIPPED' });
    expect((await asWorker(() => getFulfillmentQueue())).orders).toHaveLength(1);
  });

  it('refuses to ship without a TTN and delivers 30 s after shipping', async () => {
    const fresh = addOrder(paidAgo(0));
    const noTtn = await failure(asWorker(() => shipOrder(fresh.orderId)));
    expect(noTtn.status).toBe(409);
    expect(orderProblem(noTtn)).toEqual({ kind: 'payment-in-progress' });

    const ready = addOrder(paidAgo(5_000));
    await asWorker(() => getFulfillmentQueue());
    await asWorker(() => shipOrder(ready.orderId));
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(Date.now() + 31_000);
    configureHttp({ getToken: () => token('CUSTOMER') });
    expect((await getOrder(ready.orderId)).status).toBe('DELIVERED');
    configureHttp({ getToken: () => null });
  });

  it('fails the TTN without packaging and retries it once packaging is set', async () => {
    product().packaging = null;
    const order = addOrder(paidAgo(5_000));
    const queue = await asWorker(() => getFulfillmentQueue());
    expect(queue.orders[0]).toMatchObject({
      deliveryStatus: 'TTN_FAILED',
      failureReason: `packaging_data_missing:${product().productId}`,
    });
    const failed = await failure(asWorker(() => retryTtn(order.orderId)));
    expect(failed.status).toBe(424);
    expect(ttnFailure(failed)).toBe(`packaging_data_missing:${product().productId}`);

    product().packaging = { grossWeightGrams: 1, lengthCm: 1, widthCm: 1, heightCm: 1 };
    const retried = await asWorker(() => retryTtn(order.orderId));
    expect(retried.ttn).toMatch(/^2045/);
    expect((await failure(asWorker(() => retryTtn(order.orderId)))).status).toBe(409);
    expect((await failure(asWorker(() => retryTtn(crypto.randomUUID())))).status).toBe(404);
  });
});
