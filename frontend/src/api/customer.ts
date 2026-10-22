// Customer Service calls: spec ../docs/design/services-specs/customer-service-spec.md,
// types generated from ../customer-service/openapi/public.json (AGENTS.md §3).
// Every cart call answers the whole cart, ordered by `productId`.
import { pathsOf, type JsonBody, type Ok, type Received } from './contract';
import { problemOf } from './errors';
import type { components, operations, paths } from './generated/customer';
import { request, type RequestOptions } from './http';

type Auth = RequestOptions['auth'];
type Schemas = components['schemas'];
const pathFor = pathsOf<paths>();

export type CartItem = Received<Schemas['CartItemResponse']>;
export type CartItemsRequest = JsonBody<operations['replaceCart']>;
export type CartRuleProblem = Schemas['CartRuleProblem'];
export type CartViolation = CartRuleProblem['violation'];

type Cart = Received<Ok<operations['getCart']>>;

/** EP-CUST-03 (`getCart`): the cart; `[]` when there is none. */
export function getCart(auth: Auth = 'session'): Promise<Cart> {
  return request<Cart>(pathFor('/customers/cart'), { auth });
}

/** EP-CUST-04 (`addItem`): add, or increase the quantity of a line already in the cart. */
export function addCartItem(
  body: JsonBody<operations['addItem']>,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['addItem']>>> {
  return request(pathFor('/customers/cart/items'), { method: 'POST', body, auth });
}

/** EP-CUST-05 (`setItemQuantity`): set a line's quantity; `404` when the line is not in the cart. */
export function setCartItemQuantity(
  productId: string,
  quantity: number,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['setItemQuantity']>>> {
  const body: JsonBody<operations['setItemQuantity']> = { quantity };
  return request(pathFor('/customers/cart/items/{productId}', { productId }), {
    method: 'PATCH',
    body,
    auth,
  });
}

/** EP-CUST-06 (`removeItem`): remove a line; idempotent. */
export function removeCartItem(
  productId: string,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['removeItem']>>> {
  return request(pathFor('/customers/cart/items/{productId}', { productId }), {
    method: 'DELETE',
    auth,
  });
}

/** EP-CUST-07 (`replaceCart`): the cart becomes exactly `items`; idempotent. */
export function replaceCart(
  body: CartItemsRequest,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['replaceCart']>>> {
  return request(pathFor('/customers/cart'), { method: 'PUT', body, auth });
}

/** EP-CUST-08 (`mergeIntoCart`): quantities are summed. Not idempotent: never resent automatically. */
export function mergeCart(
  body: JsonBody<operations['mergeIntoCart']>,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['mergeIntoCart']>>> {
  return request(pathFor('/customers/cart/merge'), { method: 'POST', body, auth });
}

/** EP-CUST-09 (`clearCart`): remove every line; answers `[]`. */
export function clearCart(auth: Auth = 'session'): Promise<Received<Ok<operations['clearCart']>>> {
  return request(pathFor('/customers/cart'), { method: 'DELETE', auth });
}

/** The `422` cart-rule problem of `error`, or `undefined` for any other error (design D3). */
export function cartViolation(error: unknown) {
  return problemOf<CartRuleProblem>(error, 'cart-rule-violation');
}
