// Order Service calls: spec ../docs/design/services-specs/order-service-spec.md,
// types generated from ../order-service/openapi/public.json (AGENTS.md §3).
import {
  pathsOf,
  type HeaderParams,
  type JsonBody,
  type Ok,
  type Page,
  type Query,
  type Received,
} from './contract';
import { ApiError, problemOf } from './errors';
import type { components, operations, paths } from './generated/order';
import { request } from './http';

type Schemas = components['schemas'];
const pathFor = pathsOf<paths>();

export type OrderStatus = Schemas['OrderSummaryResponse']['status'];
export type DeliveryStatus = NonNullable<Schemas['OrderDetailsResponse']['deliveryStatus']>;
export type OrderSummary = Received<Schemas['OrderSummaryResponse']>;
export type OrderDetails = Received<Schemas['OrderDetailsResponse']>;
export type PlacedOrder = Received<Schemas['PlacedOrderResponse']>;
export type PlaceOrderRequest = JsonBody<operations['place']>;
export type DeliveryDetails = PlaceOrderRequest['deliveryDetails'];
export type CardDetails = JsonBody<operations['pay']>['cardDetails'];
export type OrderItemRequest = PlaceOrderRequest['items'][number];

/** EP-ORD-01 (`validate`): soft check of the cart; `204`, or `422` ineligible items. */
export function validateCheckout(items: OrderItemRequest[]): Promise<void> {
  const body: JsonBody<operations['validate']> = { items };
  return request<void>(pathFor('/orders/validate'), { method: 'POST', body });
}

/**
 * EP-ORD-02 (`place`): place the order and reserve stock. `idempotencyKey` is one
 * UUID per checkout attempt (BL-07): a repeat returns the first order.
 */
export function placeOrder(
  body: PlaceOrderRequest,
  idempotencyKey: string,
): Promise<Received<Ok<operations['place']>>> {
  const headers: Required<HeaderParams<operations['place']>> = {
    'Idempotency-Key': idempotencyKey,
  };
  return request(pathFor('/orders'), { method: 'POST', body, headers });
}

/** EP-ORD-03 (`pay`): charge the card; card data is never logged or stored. */
export function payOrder(
  orderId: string,
  cardDetails: CardDetails,
): Promise<Received<Ok<operations['pay']>>> {
  const body: JsonBody<operations['pay']> = { cardDetails };
  return request(pathFor('/orders/{orderId}/pay', { orderId }), { method: 'POST', body });
}

/** EP-ORD-04 (`history`): the customer's orders, newest first. */
export function listOrders(
  query: Query<operations['history']> = {},
  options: { signal?: AbortSignal } = {},
): Promise<Page<OrderSummary>> {
  return request<Page<OrderSummary>>(pathFor('/orders'), {
    query: { ...query },
    signal: options.signal,
  });
}

/** EP-ORD-05 (`details`): one order of the customer; `404` for another customer's order. */
export function getOrder(
  orderId: string,
  options: { signal?: AbortSignal } = {},
): Promise<OrderDetails> {
  return request<OrderDetails>(pathFor('/orders/{orderId}', { orderId }), {
    signal: options.signal,
  });
}

/** EP-ORD-06 (`cancel`): `202` with `CANCELLED`, or `CANCELLATION_REQUESTED` while the saga runs. */
export function cancelOrder(orderId: string): Promise<Received<Ok<operations['cancel']>>> {
  return request(pathFor('/orders/{orderId}', { orderId }), { method: 'DELETE' });
}

/** The problems of the order endpoints, told apart by `type` (AGENTS.md §3). */
export type OrderProblem =
  | { kind: 'ineligible-items'; items: Received<Schemas['IneligibleItem']>[] }
  | { kind: 'insufficient-stock'; items: Received<Schemas['InsufficientItem']>[] }
  | { kind: 'order-total-too-large'; maxTotal: number }
  | { kind: 'idempotency-key-mismatch' }
  | { kind: 'request-in-progress'; retryAfter?: number }
  | { kind: 'payment-declined'; reason: string }
  | { kind: 'payment-window-expired' }
  | { kind: 'order-state'; orderStatus: OrderStatus }
  | { kind: 'payment-in-progress' };

export function orderProblem(error: unknown): OrderProblem | undefined {
  const ineligible = problemOf<Schemas['IneligibleItemsProblem']>(error, 'ineligible-items');
  if (ineligible) return { kind: 'ineligible-items', items: ineligible.ineligibleItems };
  const insufficient = problemOf<Schemas['InsufficientStockProblem']>(error, 'insufficient-stock');
  if (insufficient) return { kind: 'insufficient-stock', items: insufficient.insufficientItems };
  const tooLarge = problemOf<Schemas['OrderTotalTooLargeProblem']>(error, 'order-total-too-large');
  if (tooLarge) return { kind: 'order-total-too-large', maxTotal: tooLarge.maxTotal };
  if (problemOf(error, 'idempotency-key-mismatch')) return { kind: 'idempotency-key-mismatch' };
  if (problemOf(error, 'request-in-progress')) {
    return { kind: 'request-in-progress', retryAfter: (error as ApiError).retryAfter };
  }
  const declined = problemOf<Schemas['PaymentDeclinedProblem']>(error, 'payment-declined');
  if (declined) return { kind: 'payment-declined', reason: declined.reason };
  if (problemOf(error, 'payment-window-expired')) return { kind: 'payment-window-expired' };
  // Two `conflict` problems: an order in the wrong state carries `orderStatus`; an
  // unresolved earlier payment attempt does not.
  const conflict = problemOf<Schemas['OrderStateProblem']>(error, 'conflict');
  if (conflict) {
    return conflict.orderStatus == null
      ? { kind: 'payment-in-progress' }
      : { kind: 'order-state', orderStatus: conflict.orderStatus };
  }
  return undefined;
}
