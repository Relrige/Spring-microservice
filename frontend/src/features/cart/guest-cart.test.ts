import { describe, expect, it, vi } from 'vitest';
import { GUEST_CART_KEY, readGuestCart, subscribeGuestCart, writeGuestCart } from './guest-cart';

const A = '00000000-0000-4000-8000-000000000001';
const B = '00000000-0000-4000-8000-000000000002';

describe('Guest cart kept in the browser', () => {
  it('Reload keeps the guest cart: the stored value is read back', () => {
    writeGuestCart([
      { productId: A, quantity: 1 },
      { productId: B, quantity: 2 },
    ]);
    const stored = window.localStorage.getItem(GUEST_CART_KEY)!;
    expect(JSON.parse(stored)).toMatchObject({ version: 1 });
    // A reload starts from storage only.
    window.localStorage.setItem(GUEST_CART_KEY, stored.replace('"version":1', '"version": 1'));
    expect(readGuestCart()).toEqual([
      { productId: A, quantity: 1 },
      { productId: B, quantity: 2 },
    ]);
  });

  it('Change in another tab: a storage event notifies and the new value is read', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeGuestCart(listener);
    window.localStorage.setItem(
      GUEST_CART_KEY,
      JSON.stringify({ version: 1, items: [{ productId: A, quantity: 3 }] }),
    );
    window.dispatchEvent(new StorageEvent('storage', { key: GUEST_CART_KEY }));
    expect(listener).toHaveBeenCalledTimes(1);
    expect(readGuestCart()).toEqual([{ productId: A, quantity: 3 }]);
    window.dispatchEvent(new StorageEvent('storage', { key: 'other' }));
    expect(listener).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it.each([
    'not json',
    JSON.stringify({ version: 2, items: [] }),
    JSON.stringify({ version: 1, items: [{ productId: 'x', quantity: 1 }] }),
    JSON.stringify({ version: 1, items: [{ productId: A, quantity: 11 }] }),
    JSON.stringify({ version: 1, items: [{ productId: A, quantity: 1.5 }] }),
    JSON.stringify({
      version: 1,
      items: [
        { productId: A, quantity: 1 },
        { productId: A, quantity: 1 },
      ],
    }),
  ])('Unreadable stored cart: %s is an empty cart', (raw) => {
    window.localStorage.setItem(GUEST_CART_KEY, raw);
    expect(readGuestCart()).toEqual([]);
  });

  it('removes the key when the cart becomes empty and returns a stable snapshot', () => {
    writeGuestCart([{ productId: A, quantity: 1 }]);
    expect(readGuestCart()).toBe(readGuestCart());
    writeGuestCart([]);
    expect(window.localStorage.getItem(GUEST_CART_KEY)).toBeNull();
  });
});
