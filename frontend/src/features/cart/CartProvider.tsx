// One cart interface for the header and pages, whatever the owner (design D1): the
// guest cart in the browser, the customer's cart at Customer Service, or none for staff.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import {
  addCartItem,
  cartViolation,
  clearCart,
  getCart,
  removeCartItem,
  setCartItemQuantity,
  type CartItem,
} from '../../api/customer';
import { ApiError, userMessage } from '../../api/errors';
import { useSession } from '../../session/SessionProvider';
import { addItem, CART_MESSAGES, removeItem, setItemQuantity, type RuleResult } from './cart-rules';
import { readGuestCart, useGuestCart, writeGuestCart } from './guest-cart';

export type CartOwner = 'guest' | 'customer' | 'none';
export type CartChange = { ok: true } | { ok: false; message: string };

export const LINE_GONE = 'This product is no longer in your cart.';

export const cartQueryKey = ['cart'] as const;

/** EP-CUST-03 as a query; the cart page adds `refetchOnMount: 'always'` (design D4). */
export const cartQuery = {
  queryKey: cartQueryKey,
  queryFn: () => getCart(),
  refetchOnWindowFocus: true,
} as const;

interface CartContextValue {
  owner: CartOwner;
  /** Ordered by productId; empty for staff. */
  lines: CartItem[];
  /** `loading` / `error` only for the server cart's first read. */
  status: 'loading' | 'ready' | 'error';
  error: unknown;
  reload: () => void;
  /** Sum of all quantities (header badge, design D5). */
  count: number;
  /** A change of this line (or of the whole cart) is waiting for its answer. */
  busy: (productId: string) => boolean;
  add: (productId: string, quantity: number) => Promise<CartChange>;
  setQuantity: (productId: string, quantity: number) => Promise<CartChange>;
  remove: (productId: string) => Promise<CartChange>;
  clear: () => Promise<CartChange>;
}

const CartContext = createContext<CartContextValue | null>(null);

const ALL = '*';

/** The message for a failed server change (design D4); 401 has already ended the session. */
function changeMessage(error: unknown): string {
  const problem = cartViolation(error);
  if (problem) return CART_MESSAGES[problem.violation];
  if (error instanceof ApiError && error.kind === 'not-found') return LINE_GONE;
  return userMessage(error);
}

function guestChange(change: (items: CartItem[]) => RuleResult): CartChange {
  const result = change(readGuestCart());
  if (!result.ok) return { ok: false, message: CART_MESSAGES[result.violation] };
  writeGuestCart(result.items);
  return { ok: true };
}

export function CartProvider({ children }: { children: ReactNode }) {
  const { session } = useSession();
  const owner: CartOwner = !session ? 'guest' : session.role === 'CUSTOMER' ? 'customer' : 'none';
  const queryClient = useQueryClient();
  const guestItems = useGuestCart();
  const query = useQuery({ ...cartQuery, enabled: owner === 'customer' });
  const [pending, setPending] = useState<string[]>([]);

  // Scope `cart`: changes run one after another, so an older answer never overwrites a newer one.
  const { mutateAsync } = useMutation({
    mutationFn: (call: () => Promise<CartItem[]>) => call(),
    scope: { id: 'cart' },
  });

  const serverChange = useCallback(
    async (id: string, call: () => Promise<CartItem[]>): Promise<CartChange> => {
      setPending((ids) => [...ids, id]);
      try {
        queryClient.setQueryData(cartQueryKey, await mutateAsync(call));
        return { ok: true };
      } catch (error) {
        // The cart may differ from what is shown: read it again. Unavailable: nothing is resent.
        if (cartViolation(error) || (error instanceof ApiError && error.kind === 'not-found')) {
          void queryClient.invalidateQueries({ queryKey: cartQueryKey });
        }
        return { ok: false, message: changeMessage(error) };
      } finally {
        setPending((ids) => {
          const index = ids.indexOf(id);
          return index < 0 ? ids : [...ids.slice(0, index), ...ids.slice(index + 1)];
        });
      }
    },
    [mutateAsync, queryClient],
  );

  const value = useMemo<CartContextValue>(() => {
    const noCart = async (): Promise<CartChange> => ({ ok: false, message: userMessage(null) });
    const lines = owner === 'guest' ? guestItems : owner === 'customer' ? (query.data ?? []) : [];
    const base = {
      owner,
      lines,
      count: lines.reduce((sum, line) => sum + line.quantity, 0),
      busy: (productId: string) => pending.includes(productId) || pending.includes(ALL),
      reload: () => void query.refetch(),
    };
    if (owner === 'guest') {
      return {
        ...base,
        status: 'ready',
        error: null,
        add: async (productId, quantity) =>
          guestChange((items) => addItem(items, productId, quantity)),
        setQuantity: async (productId, quantity) =>
          guestChange((items) => setItemQuantity(items, productId, quantity)),
        remove: async (productId) => guestChange((items) => removeItem(items, productId)),
        clear: async () => guestChange(() => ({ ok: true, items: [] })),
      };
    }
    if (owner === 'customer') {
      return {
        ...base,
        status: query.data ? 'ready' : query.isError ? 'error' : 'loading',
        error: query.error,
        add: (productId, quantity) =>
          serverChange(productId, () => addCartItem({ productId, quantity })),
        setQuantity: (productId, quantity) =>
          serverChange(productId, () => setCartItemQuantity(productId, quantity)),
        remove: (productId) => serverChange(productId, () => removeCartItem(productId)),
        clear: () => serverChange(ALL, () => clearCart()),
      };
    }
    return {
      ...base,
      status: 'ready',
      error: null,
      add: noCart,
      setQuantity: noCart,
      remove: noCart,
      clear: noCart,
    };
  }, [owner, guestItems, query, pending, serverChange]);

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) throw new Error('useCart must be used inside CartProvider');
  return value;
}
