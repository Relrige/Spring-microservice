import { describe, expect, expectTypeOf, it } from 'vitest';
import { ApiError, MESSAGES, problemOf, userMessage, type ProblemDetail } from './errors';
import type { Received } from './contract';
import type { components } from './generated/order';

type Order = components['schemas'];

const insufficientStock: ProblemDetail = {
  type: 'https://voltstore.com/errors/insufficient-stock',
  title: 'Unprocessable Entity',
  status: 422,
  detail: 'Not enough stock',
  insufficientItems: [{ productId: 'p1', requested: 3, available: 1 }],
};

describe('problemOf', () => {
  it('Several problem kinds on one status: the type decides the schema', () => {
    const error = new ApiError('unprocessable', { status: 422, problem: insufficientStock });

    const stock = problemOf<Order['InsufficientStockProblem']>(error, 'insufficient-stock');
    expect(stock?.insufficientItems).toEqual([{ productId: 'p1', requested: 3, available: 1 }]);
    expectTypeOf(stock).toEqualTypeOf<Received<Order['InsufficientStockProblem']> | undefined>();

    expect(problemOf<Order['IneligibleItemsProblem']>(error, 'ineligible-items')).toBeUndefined();
    expect(
      problemOf<Order['OrderTotalTooLargeProblem']>(error, 'order-total-too-large'),
    ).toBeUndefined();
  });

  it('Unknown problem type: no match, and the message follows the status', () => {
    const error = new ApiError('unprocessable', {
      status: 422,
      problem: { type: 'https://voltstore.com/errors/something-new', status: 422 },
    });
    expect(
      problemOf<Order['InsufficientStockProblem']>(error, 'insufficient-stock'),
    ).toBeUndefined();
    expect(userMessage(error)).toBe(MESSAGES.unprocessable);
  });

  it('returns nothing for errors that are not ApiError or carry no problem', () => {
    expect(problemOf(new Error('boom'), 'insufficient-stock')).toBeUndefined();
    expect(problemOf(new ApiError('network'), 'insufficient-stock')).toBeUndefined();
  });
});
