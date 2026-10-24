import { describe, expect, expectTypeOf, it } from 'vitest';
import type { Received } from './contract';
import { ttnFailure } from './delivery';
import { ApiError, PROBLEM_TYPE_PREFIX } from './errors';
import type { components } from './generated/order';
import type { FulfillmentQueue, QueueEntry } from './order';

describe('worker API types', () => {
  it('queue aliases match the generated types', () => {
    expectTypeOf<FulfillmentQueue>().toEqualTypeOf<
      Received<components['schemas']['FulfillmentQueueResponse']>
    >();
    expectTypeOf<QueueEntry>().toEqualTypeOf<
      Received<components['schemas']['FulfillmentQueueEntry']>
    >();
  });
});

describe('ttnFailure', () => {
  const failed = (type: string) =>
    new ApiError('unprocessable', {
      status: 424,
      problem: { type: `${PROBLEM_TYPE_PREFIX}${type}`, title: 't', status: 424, reason: 'x:1' },
    });

  it('reads the reason of a ttn-generation-failed problem and nothing else', () => {
    expect(ttnFailure(failed('ttn-generation-failed'))).toBe('x:1');
    expect(ttnFailure(failed('conflict'))).toBeUndefined();
    expect(ttnFailure(new ApiError('network'))).toBeUndefined();
  });
});
