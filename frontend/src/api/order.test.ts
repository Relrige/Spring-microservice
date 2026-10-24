import { http, HttpResponse } from 'msw';
import { describe, expect, expectTypeOf, it } from 'vitest';
import { server } from '../test/server';
import type { Received } from './contract';
import { ApiError, PROBLEM_TYPE_PREFIX } from './errors';
import type { components } from './generated/order';
import {
  orderProblem,
  placeOrder,
  type CardDetails,
  type DeliveryDetails,
  type OrderDetails,
  type OrderStatus,
  type PlaceOrderRequest,
} from './order';

type Schemas = components['schemas'];

describe('order API types', () => {
  it('aliases and request bodies match the generated types', () => {
    expectTypeOf<OrderDetails>().toEqualTypeOf<Received<Schemas['OrderDetailsResponse']>>();
    expectTypeOf<PlaceOrderRequest>().toEqualTypeOf<Schemas['PlaceOrderRequest']>();
    expectTypeOf<DeliveryDetails>().toEqualTypeOf<Schemas['DeliveryDetailsRequest']>();
    expectTypeOf<CardDetails>().toEqualTypeOf<Schemas['CardDetailsRequest']>();
    expectTypeOf<OrderStatus>().toEqualTypeOf<Schemas['OrderDetailsResponse']['status']>();
  });
});

describe('orderProblem', () => {
  const problem = (status: number, kind: string, extra: Record<string, unknown> = {}) =>
    new ApiError(status === 402 ? 'payment' : status === 409 ? 'conflict' : 'unprocessable', {
      status,
      retryAfter: status === 409 ? 1 : undefined,
      problem: { type: `${PROBLEM_TYPE_PREFIX}${kind}`, title: 't', status, ...extra },
    });

  it('tells every problem kind apart by its type', () => {
    const ineligibleItems = [{ productId: 'a', reason: 'NOT_ACTIVE' }];
    expect(orderProblem(problem(422, 'ineligible-items', { ineligibleItems }))).toEqual({
      kind: 'ineligible-items',
      items: ineligibleItems,
    });
    const insufficientItems = [{ productId: 'a', requested: 3, available: 1 }];
    expect(orderProblem(problem(422, 'insufficient-stock', { insufficientItems }))).toEqual({
      kind: 'insufficient-stock',
      items: insufficientItems,
    });
    expect(orderProblem(problem(422, 'order-total-too-large', { maxTotal: 5 }))).toEqual({
      kind: 'order-total-too-large',
      maxTotal: 5,
    });
    expect(orderProblem(problem(422, 'idempotency-key-mismatch'))).toEqual({
      kind: 'idempotency-key-mismatch',
    });
    expect(orderProblem(problem(409, 'request-in-progress'))).toEqual({
      kind: 'request-in-progress',
      retryAfter: 1,
    });
    expect(orderProblem(problem(402, 'payment-declined', { reason: 'card_declined' }))).toEqual({
      kind: 'payment-declined',
      reason: 'card_declined',
    });
    expect(orderProblem(problem(409, 'payment-window-expired'))).toEqual({
      kind: 'payment-window-expired',
    });
  });

  it('splits the two conflict problems by orderStatus', () => {
    expect(orderProblem(problem(409, 'conflict', { orderStatus: 'SHIPPED' }))).toEqual({
      kind: 'order-state',
      orderStatus: 'SHIPPED',
    });
    expect(orderProblem(problem(409, 'conflict'))).toEqual({ kind: 'payment-in-progress' });
  });

  it('returns nothing for other errors', () => {
    expect(orderProblem(problem(422, 'unprocessable-entity'))).toBeUndefined();
    expect(orderProblem(new ApiError('network'))).toBeUndefined();
  });
});

describe('placeOrder', () => {
  it('sends the Idempotency-Key header', async () => {
    let key: string | null = null;
    server.use(
      http.post('*/api/orders', ({ request }) => {
        key = request.headers.get('Idempotency-Key');
        return HttpResponse.json(
          {
            orderId: 'o',
            status: 'PENDING_PAYMENT',
            totalAmount: 1,
            currency: 'UAH',
            reservationExpiresAt: '2026-10-22T12:15:00Z',
          } satisfies Schemas['PlacedOrderResponse'],
          { status: 201 },
        );
      }),
    );
    await placeOrder(
      {
        items: [{ productId: 'p', quantity: 1 }],
        deliveryDetails: {
          name: 'A B',
          phone: '+380501234567',
          address: 'x',
          carrier: 'NOVA_POSHTA',
        },
      },
      'key-1',
    );
    expect(key).toBe('key-1');
  });
});
