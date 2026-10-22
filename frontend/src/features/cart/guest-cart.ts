// The guest cart: product ids and quantities in localStorage, shared by every tab (design D2).
import { useSyncExternalStore } from 'react';
import type { CartItem } from '../../api/customer';
import { checkCart } from './cart-rules';

export const GUEST_CART_KEY = 'voltstore.guestCart';

const EMPTY: CartItem[] = [];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const listeners = new Set<() => void>();
/** The last raw value read and its items, so the snapshot is stable between changes. */
let cache: { raw: string | null; items: CartItem[] } = { raw: null, items: EMPTY };

function parse(raw: string | null): CartItem[] {
  if (!raw) return EMPTY;
  try {
    const value = JSON.parse(raw) as { version?: unknown; items?: unknown };
    if (value?.version !== 1 || !Array.isArray(value.items)) return EMPTY;
    const items = (value.items as Partial<CartItem>[]).map(({ productId, quantity }) => ({
      productId,
      quantity,
    }));
    const valid = items.every(
      ({ productId, quantity }) =>
        typeof productId === 'string' &&
        UUID.test(productId) &&
        Number.isInteger(quantity) &&
        quantity! >= 1,
    );
    const ids = new Set(items.map((item) => item.productId));
    // Spec shopping-cart, Unreadable stored cart: anything invalid drops the whole value.
    const result = valid && ids.size === items.length ? checkCart(items as CartItem[]) : undefined;
    return result?.ok && result.items.length > 0 ? result.items : EMPTY;
  } catch {
    return EMPTY;
  }
}

export function readGuestCart(): CartItem[] {
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(GUEST_CART_KEY);
  } catch {
    // storage unavailable: the cart lives in memory only
    return cache.items;
  }
  if (raw !== cache.raw) cache = { raw, items: parse(raw) };
  return cache.items;
}

export function writeGuestCart(items: CartItem[]): void {
  const raw = items.length > 0 ? JSON.stringify({ version: 1, items }) : null;
  cache = { raw, items: items.length > 0 ? items : EMPTY };
  try {
    if (raw) window.localStorage.setItem(GUEST_CART_KEY, raw);
    else window.localStorage.removeItem(GUEST_CART_KEY);
  } catch {
    // storage unavailable (private mode): kept in memory for this tab
  }
  for (const listener of listeners) listener();
}

/** Notifies on changes in this tab and, through `storage` events, in other tabs. */
export function subscribeGuestCart(listener: () => void): () => void {
  const onStorage = (event: StorageEvent) => {
    if (event.key === GUEST_CART_KEY || event.key === null) listener();
  };
  listeners.add(listener);
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

export function useGuestCart(): CartItem[] {
  return useSyncExternalStore(subscribeGuestCart, readGuestCart, () => EMPTY);
}
