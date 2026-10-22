// Cart rules of Customer Service (INV-CART-01/02), shared by the guest cart and the
// mock backend (design D2). Checked on the result of a change, as the service does.
import type { CartItem, CartViolation } from '../../api/customer';
import { MESSAGES } from '../../api/errors';

export const MAX_PRODUCTS = 10;
export const MAX_QUANTITY = 10;

export type RuleResult =
  | { ok: true; items: CartItem[] }
  | {
      ok: false;
      violation: CartViolation;
      /** QUANTITY_LIMIT_EXCEEDED, DUPLICATE_PRODUCT: the products concerned, sorted. */
      productIds?: string[];
      /** TOO_MANY_PRODUCTS: distinct products the cart would contain. */
      resultingProductCount?: number;
    };

/** One message per violation; a code the backend adds fails the build until it has one. */
export const CART_MESSAGES: Record<CartViolation, string> = {
  TOO_MANY_PRODUCTS: `Your cart can hold up to ${MAX_PRODUCTS} different products.`,
  QUANTITY_LIMIT_EXCEEDED: `You can have up to ${MAX_QUANTITY} of each product in your cart.`,
  // Cannot happen from this client: the guest cart holds each product once.
  DUPLICATE_PRODUCT: MESSAGES.unexpected,
};

const byProductId = (a: CartItem, b: CartItem) =>
  a.productId < b.productId ? -1 : a.productId > b.productId ? 1 : 0;

function assertQuantity(quantity: number) {
  // The service answers 400 for these; the UI never sends them.
  if (!Number.isInteger(quantity) || quantity < 1) throw new RangeError(`Bad quantity ${quantity}`);
}

/** Checks the invariants on a resulting cart; the items come back ordered by `productId`. */
export function checkCart(items: CartItem[]): RuleResult {
  if (items.length > MAX_PRODUCTS) {
    return { ok: false, violation: 'TOO_MANY_PRODUCTS', resultingProductCount: items.length };
  }
  const over = items.filter((item) => item.quantity > MAX_QUANTITY);
  if (over.length > 0) {
    return {
      ok: false,
      violation: 'QUANTITY_LIMIT_EXCEEDED',
      productIds: over.map((item) => item.productId).sort(),
    };
  }
  return { ok: true, items: [...items].sort(byProductId) };
}

/** EP-CUST-04: a product already in the cart gets its quantity increased. */
export function addItem(items: CartItem[], productId: string, quantity: number): RuleResult {
  return mergeItems(items, [{ productId, quantity }]);
}

/** EP-CUST-05: sets the quantity; a line that is not in the cart is left out. */
export function setItemQuantity(
  items: CartItem[],
  productId: string,
  quantity: number,
): RuleResult {
  assertQuantity(quantity);
  return checkCart(
    items.map((item) => (item.productId === productId ? { productId, quantity } : item)),
  );
}

/** EP-CUST-06: idempotent. */
export function removeItem(items: CartItem[], productId: string): RuleResult {
  return checkCart(items.filter((item) => item.productId !== productId));
}

/** EP-CUST-08: quantities of products already in the cart are summed. */
export function mergeItems(items: CartItem[], added: CartItem[]): RuleResult {
  const merged = new Map(items.map((item) => [item.productId, item.quantity]));
  for (const { productId, quantity } of added) {
    assertQuantity(quantity);
    merged.set(productId, (merged.get(productId) ?? 0) + quantity);
  }
  return checkCart([...merged].map(([productId, quantity]) => ({ productId, quantity })));
}

/** The products listed more than once in a batch request, sorted (`DUPLICATE_PRODUCT`). */
export function duplicateProducts(items: CartItem[]): string[] {
  const seen = new Set<string>();
  const twice = new Set<string>();
  for (const { productId } of items) (seen.has(productId) ? twice : seen).add(productId);
  return [...twice].sort();
}
