import { describe, expect, it } from 'vitest';
import {
  addItem,
  CART_MESSAGES,
  duplicateProducts,
  mergeItems,
  removeItem,
  setItemQuantity,
} from './cart-rules';

const id = (n: number) => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const tenProducts = Array.from({ length: 10 }, (_, i) => ({ productId: id(i + 1), quantity: 1 }));

describe('Cart limits', () => {
  it('Eleventh product: rejected with TOO_MANY_PRODUCTS and its message', () => {
    const result = addItem(tenProducts, id(11), 1);
    expect(result).toMatchObject({
      ok: false,
      violation: 'TOO_MANY_PRODUCTS',
      resultingProductCount: 11,
    });
    expect(CART_MESSAGES.TOO_MANY_PRODUCTS).toBe('Your cart can hold up to 10 different products.');
  });

  it('Quantity above ten: 8 + 3 of one product is rejected', () => {
    const result = addItem([{ productId: id(1), quantity: 8 }], id(1), 3);
    expect(result).toMatchObject({
      ok: false,
      violation: 'QUANTITY_LIMIT_EXCEEDED',
      productIds: [id(1)],
    });
    expect(CART_MESSAGES.QUANTITY_LIMIT_EXCEEDED).toBe(
      'You can have up to 10 of each product in your cart.',
    );
  });

  it('adding to an existing line sums the quantity; a full cart still accepts it', () => {
    expect(addItem(tenProducts, id(3), 9)).toMatchObject({ ok: true });
    const result = addItem([{ productId: id(2), quantity: 2 }], id(2), 3);
    expect(result).toEqual({ ok: true, items: [{ productId: id(2), quantity: 5 }] });
  });

  it('sets quantities from 1 to 10: 11 is a violation, 0 is not accepted at all', () => {
    const items = [{ productId: id(1), quantity: 2 }];
    expect(setItemQuantity(items, id(1), 10)).toEqual({
      ok: true,
      items: [{ productId: id(1), quantity: 10 }],
    });
    expect(setItemQuantity(items, id(1), 11)).toMatchObject({
      violation: 'QUANTITY_LIMIT_EXCEEDED',
    });
    expect(() => setItemQuantity(items, id(1), 0)).toThrow(RangeError);
    expect(() => addItem(items, id(1), 0)).toThrow(RangeError);
  });

  it('keeps the items ordered by productId, removes idempotently and finds duplicates', () => {
    const added = addItem([{ productId: id(5), quantity: 1 }], id(2), 1);
    expect(added.ok && added.items.map((i) => i.productId)).toEqual([id(2), id(5)]);
    expect(removeItem([{ productId: id(5), quantity: 1 }], id(9))).toMatchObject({ ok: true });
    expect(
      mergeItems([{ productId: id(1), quantity: 1 }], [{ productId: id(1), quantity: 2 }]),
    ).toEqual({ ok: true, items: [{ productId: id(1), quantity: 3 }] });
    expect(
      duplicateProducts([
        { productId: id(2), quantity: 1 },
        { productId: id(2), quantity: 1 },
      ]),
    ).toEqual([id(2)]);
  });
});
