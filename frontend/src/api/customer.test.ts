import { describe, expect, expectTypeOf, it } from 'vitest';
import type { Received } from './contract';
import {
  cartViolation,
  type CartItem,
  type CartItemsRequest,
  type CartViolation,
  type Profile,
  type ProfileInput,
} from './customer';
import { ApiError, PROBLEM_TYPE_PREFIX } from './errors';
import type { components } from './generated/customer';

type Schemas = components['schemas'];

describe('customer API types', () => {
  it('CartItem and the request bodies match the generated types', () => {
    expectTypeOf<CartItem>().toEqualTypeOf<Received<Schemas['CartItemResponse']>>();
    expectTypeOf<CartItemsRequest>().toEqualTypeOf<Schemas['CartItemsRequest']>();
    expectTypeOf<Profile>().toEqualTypeOf<Received<Schemas['ProfileResponse']>>();
    expectTypeOf<ProfileInput>().toEqualTypeOf<Schemas['ProfileRequest']>();
    expectTypeOf<CartViolation>().toEqualTypeOf<
      'TOO_MANY_PRODUCTS' | 'QUANTITY_LIMIT_EXCEEDED' | 'DUPLICATE_PRODUCT'
    >();
  });
});

describe('cartViolation', () => {
  const unprocessable = (type: string, extra: Record<string, unknown> = {}) =>
    new ApiError('unprocessable', {
      status: 422,
      problem: { type, title: 'Unprocessable Entity', status: 422, ...extra },
    });

  it.each<CartViolation>(['TOO_MANY_PRODUCTS', 'QUANTITY_LIMIT_EXCEEDED', 'DUPLICATE_PRODUCT'])(
    'reads %s from a cart-rule-violation problem',
    (violation) => {
      const error = unprocessable(`${PROBLEM_TYPE_PREFIX}cart-rule-violation`, { violation });
      expect(cartViolation(error)?.violation).toBe(violation);
    },
  );

  it('returns nothing for a 422 with another type and for other errors', () => {
    const other = unprocessable(`${PROBLEM_TYPE_PREFIX}unprocessable-entity`, {
      violation: 'TOO_MANY_PRODUCTS',
    });
    expect(cartViolation(other)).toBeUndefined();
    expect(cartViolation(new ApiError('network'))).toBeUndefined();
    expect(cartViolation(new Error('boom'))).toBeUndefined();
  });
});
