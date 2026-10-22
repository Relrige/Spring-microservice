import { describe, expect, it } from 'vitest';
import { estimatedSubtotal } from './subtotal';

describe('estimated subtotal', () => {
  it('sums line totals in kopecks without floating-point drift', () => {
    expect(0.1 + 0.2).not.toBe(0.3);
    expect(
      estimatedSubtotal([
        { price: 0.1, quantity: 1 },
        { price: 0.2, quantity: 1 },
      ]),
    ).toBe(0.3);
    expect(
      estimatedSubtotal([
        { price: 12999, quantity: 1 },
        { price: 4899, quantity: 2 },
      ]),
    ).toBe(22797);
    expect(estimatedSubtotal([])).toBe(0);
  });
});
