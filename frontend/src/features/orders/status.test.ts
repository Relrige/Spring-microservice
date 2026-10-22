import { describe, expect, expectTypeOf, it } from 'vitest';
import { formatDateTime, formatTime } from '../../components/DateTime';
import type { components } from '../../api/generated/order';
import { DELIVERY_STATUS, ORDER_STATUS } from './status';

type Schemas = components['schemas'];

describe('order display', () => {
  it('formats dates as 18 Oct 2026, 14:05 in local time', () => {
    const date = new Date(2026, 9, 18, 14, 5);
    expect(formatDateTime(date)).toBe('18 Oct 2026, 14:05');
    expect(formatDateTime(date.toISOString())).toBe('18 Oct 2026, 14:05');
    expect(formatTime(date)).toBe('14:05');
  });

  it('has a word for every generated order and delivery status', () => {
    expectTypeOf<keyof typeof ORDER_STATUS>().toEqualTypeOf<
      Schemas['OrderDetailsResponse']['status']
    >();
    expectTypeOf<keyof typeof DELIVERY_STATUS>().toEqualTypeOf<
      NonNullable<Schemas['OrderDetailsResponse']['deliveryStatus']>
    >();
    expect(ORDER_STATUS.PENDING_SHIPPING.label).toBe('Ready to ship');
  });
});
