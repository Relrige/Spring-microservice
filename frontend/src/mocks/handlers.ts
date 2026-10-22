// MSW handlers of the mock backend (design D10). Paths are the gateway's paths behind /api.
// Each handler follows the spec section named next to it; status codes and problem
// details match ../docs/design/services-specs/. Every JSON answer is checked with
// `satisfies` against the types generated from the services' OpenAPI documents.
import { delay, http, HttpResponse, type HttpHandler } from 'msw';
import type { Role } from '../api/auth';
import type { ProductStatus } from '../api/catalog';
import { PROBLEM_TYPE_PREFIX } from '../api/errors';
import type { components as AuthComponents } from '../api/generated/auth';
import type { components as CatalogComponents } from '../api/generated/catalog';
import type { components as CustomerComponents } from '../api/generated/customer';
import type { components as OrderComponents } from '../api/generated/order';
import type { Received } from '../api/contract';
import type { CartItem } from '../api/customer';
import {
  addItem,
  CART_MESSAGES,
  duplicateProducts,
  mergeItems,
  MAX_PRODUCTS,
  MAX_QUANTITY,
  removeItem,
  setItemQuantity,
  checkCart,
  type RuleResult,
} from '../features/cart/cart-rules';
import { db, type MockOrder, type MockUser } from './db';
import { createMockToken, readMockToken, type MockClaims } from './tokens';

const api = (path: string) => `*/api${path}`;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Catalog's PostgreSQL orders text by code point (`ASUS…` before `Acer…`),
 * not by locale; the mock does the same (found in the live run, task 8.3).
 */
export function compareText(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

type AuthSchemas = AuthComponents['schemas'];
type CatalogSchemas = CatalogComponents['schemas'];
type CustomerSchemas = CustomerComponents['schemas'];

/** Default `type` kind and title per status, as the services' exception handlers write them. */
const KINDS: Record<number, [string, string]> = {
  400: ['validation', 'Bad Request'],
  401: ['unauthorized', 'Unauthorized'],
  402: ['payment-declined', 'Payment Required'],
  403: ['forbidden', 'Forbidden'],
  404: ['not-found', 'Not Found'],
  409: ['conflict', 'Conflict'],
  422: ['unprocessable-entity', 'Unprocessable Entity'],
  503: ['service-unavailable', 'Service Unavailable'],
};

/**
 * A problem-details answer. `kind` overrides the default `type` kind of the
 * status, for problems with their own schema (e.g. `cart-rule-violation`).
 */
export function problem(
  status: number,
  detail: string,
  extra: Record<string, unknown> = {},
  service = 'mock',
  kind?: string,
  headers: Record<string, string> = {},
) {
  const [defaultKind, title] = KINDS[status] ?? ['internal-error', 'Internal Server Error'];
  const body = {
    type: `${PROBLEM_TYPE_PREFIX}${kind ?? defaultKind}`,
    title,
    status,
    detail,
    timestamp: new Date().toISOString(),
    service,
  } satisfies CatalogSchemas['Problem'];
  return HttpResponse.json(
    { ...body, ...extra },
    { status, headers: { ...headers, 'Content-Type': 'application/problem+json' } },
  );
}

type AuthMode = 'public' | 'optional' | { required: Role[] };

/** Gateway request pipeline step 3–4: who is calling, or the 401/403 answer. */
function authenticate(
  request: Request,
  mode: AuthMode,
): { claims?: MockClaims; denied?: Response } {
  if (mode === 'public') return {};
  const header = request.headers.get('Authorization');
  const token = header?.match(/^Bearer (\S+)$/i)?.[1];
  const claims = token ? readMockToken(token) : undefined;
  if (mode === 'optional') {
    if (header && !claims)
      return { denied: problem(401, 'Authentication required', {}, 'api-gateway') };
    return { claims };
  }
  if (!claims) return { denied: problem(401, 'Authentication required', {}, 'api-gateway') };
  if (!mode.required.includes(claims.role)) {
    return { denied: problem(403, 'Access denied', {}, 'api-gateway') };
  }
  return { claims };
}

async function readJson(request: Request): Promise<Record<string, unknown> | undefined> {
  try {
    const body: unknown = await request.json();
    return body && typeof body === 'object' ? (body as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}

function credentialErrors(body: Record<string, unknown> | undefined): Record<string, string[]> {
  const errors: Record<string, string[]> = {};
  const email = typeof body?.email === 'string' ? body.email.trim() : '';
  const password = typeof body?.password === 'string' ? body.password : '';
  if (!email) errors.email = ['must not be blank'];
  else if (!EMAIL.test(email)) errors.email = ['must be a well-formed email address'];
  else if (email.length > 256) errors.email = ['size must be between 0 and 256'];
  if (!password.trim()) errors.password = ['must not be blank'];
  else if (password.length < 5 || password.length > 64) {
    errors.password = ['size must be between 5 and 64'];
  }
  return errors;
}

function createUser(email: string, password: string, role: Role): MockUser | undefined {
  const normalized = email.trim().toLowerCase();
  if (db.users.some((user) => user.email === normalized)) return undefined;
  const user: MockUser = { id: crypto.randomUUID(), email: normalized, password, role };
  db.users.push(user);
  return user;
}

function intParam(value: string | null, fallback: number): number | undefined {
  if (value === null) return fallback;
  return /^\d+$/.test(value) ? Number(value) : undefined;
}

function priceParam(value: string | null): number | null | undefined {
  if (value === null || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

// Customer spec, Cart Aggregate: every answer is the whole cart, ordered by productId.
const CUSTOMER_ONLY: AuthMode = { required: ['CUSTOMER'] };

function cartAnswer(items: CartItem[]) {
  return HttpResponse.json(items satisfies CustomerSchemas['CartItemResponse'][]);
}

/** Applies a rule result: the new cart, or the `422` cart-rule problem with the cart unchanged. */
function applyCart(userId: string, result: RuleResult) {
  if (!result.ok) {
    const { ok: _ok, ...details } = result;
    const extra = {
      ...details,
      ...(result.violation === 'TOO_MANY_PRODUCTS' ? { maxProducts: MAX_PRODUCTS } : {}),
      ...(result.violation === 'QUANTITY_LIMIT_EXCEEDED' ? { maxQuantity: MAX_QUANTITY } : {}),
    } satisfies Partial<CustomerSchemas['CartRuleProblem']>;
    return problem(
      422,
      CART_MESSAGES[result.violation],
      extra,
      'customer-service',
      'cart-rule-violation',
    );
  }
  db.carts.set(userId, result.items);
  return cartAnswer(result.items);
}

const cartOf = (userId: string) => db.carts.get(userId) ?? [];

function quantityErrors(value: unknown, field: string, errors: Record<string, string[]>) {
  if (value === undefined || value === null) errors[field] = ['must not be null'];
  else if (typeof value !== 'number' || !Number.isInteger(value))
    errors[field] = ['must be an integer'];
  else if (value < 1) errors[field] = ['must be greater than or equal to 1'];
}

function productIdErrors(value: unknown, field: string, errors: Record<string, string[]>) {
  if (value === undefined || value === null) errors[field] = ['must not be null'];
  else if (typeof value !== 'string' || !UUID.test(value)) errors[field] = ['must be a valid UUID'];
}

/** EP-CUST-07/08 body: `items` non-null, every item well-formed; `undefined` = 400 errors. */
function readItems(body: Record<string, unknown> | undefined, errors: Record<string, string[]>) {
  if (!Array.isArray(body?.items)) {
    errors.items = ['must not be null'];
    return undefined;
  }
  body.items.forEach((item: unknown, index) => {
    const entry = (item ?? undefined) as Record<string, unknown> | undefined;
    if (!entry) errors[`items[${index}]`] = ['must not be null'];
    else {
      productIdErrors(entry.productId, `items[${index}].productId`, errors);
      quantityErrors(entry.quantity, `items[${index}].quantity`, errors);
    }
  });
  return Object.keys(errors).length > 0 ? undefined : (body.items as CartItem[]);
}

const cartHandlers: HttpHandler[] = [
  // EP-CUST-03: get my cart.
  http.get(api('/customers/cart'), ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    return cartAnswer(cartOf(claims!.sub));
  }),

  // EP-CUST-04: add an item.
  http.post(api('/customers/cart/items'), async ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const body = await readJson(request);
    if (!body) return problem(400, 'Malformed JSON request', {}, 'customer-service');
    const errors: Record<string, string[]> = {};
    productIdErrors(body.productId, 'productId', errors);
    quantityErrors(body.quantity, 'quantity', errors);
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', { errors }, 'customer-service');
    }
    const userId = claims!.sub;
    return applyCart(
      userId,
      addItem(cartOf(userId), String(body.productId), Number(body.quantity)),
    );
  }),

  // EP-CUST-05: set a quantity; 404 when the line is not in the cart.
  http.patch(api('/customers/cart/items/:productId'), async ({ request, params }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const productId = String(params.productId);
    if (!UUID.test(productId))
      return problem(404, 'No route matches the request.', {}, 'api-gateway');
    const body = await readJson(request);
    if (!body) return problem(400, 'Malformed JSON request', {}, 'customer-service');
    const errors: Record<string, string[]> = {};
    quantityErrors(body.quantity, 'quantity', errors);
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', { errors }, 'customer-service');
    }
    const userId = claims!.sub;
    const cart = cartOf(userId);
    if (!cart.some((item) => item.productId === productId)) {
      return problem(404, 'Product is not in the cart', {}, 'customer-service');
    }
    return applyCart(userId, setItemQuantity(cart, productId, Number(body.quantity)));
  }),

  // EP-CUST-06: remove an item; idempotent.
  http.delete(api('/customers/cart/items/:productId'), ({ request, params }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const productId = String(params.productId);
    if (!UUID.test(productId))
      return problem(404, 'No route matches the request.', {}, 'api-gateway');
    const userId = claims!.sub;
    return applyCart(userId, removeItem(cartOf(userId), productId));
  }),

  // EP-CUST-07: replace the cart; duplicates are rejected before the rules.
  http.put(api('/customers/cart'), async ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const errors: Record<string, string[]> = {};
    const items = readItems(await readJson(request), errors);
    if (!items) return problem(400, 'Validation failed', { errors }, 'customer-service');
    const duplicates = duplicateProducts(items);
    if (duplicates.length > 0) {
      return applyCart(claims!.sub, {
        ok: false,
        violation: 'DUPLICATE_PRODUCT',
        productIds: duplicates,
      });
    }
    const clean = items.map(({ productId, quantity }) => ({ productId, quantity }));
    return applyCart(claims!.sub, checkCart(clean));
  }),

  // EP-CUST-08: merge items; an empty list answers the cart as it is.
  http.post(api('/customers/cart/merge'), async ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const errors: Record<string, string[]> = {};
    const items = readItems(await readJson(request), errors);
    if (!items) return problem(400, 'Validation failed', { errors }, 'customer-service');
    const duplicates = duplicateProducts(items);
    const userId = claims!.sub;
    if (duplicates.length > 0) {
      return applyCart(userId, {
        ok: false,
        violation: 'DUPLICATE_PRODUCT',
        productIds: duplicates,
      });
    }
    if (items.length === 0) return cartAnswer(cartOf(userId));
    return applyCart(userId, mergeItems(cartOf(userId), items));
  }),

  // EP-CUST-09: clear the cart; idempotent.
  http.delete(api('/customers/cart'), ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    return applyCart(claims!.sub, { ok: true, items: [] });
  }),
];

// Customer spec EP-CUST-01/02: the profile.
const PHONE = /^\+[1-9]\d{7,14}$/;

const profileHandlers: HttpHandler[] = [
  http.get(api('/customers/me/profile'), ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const profile = db.profiles.get(claims!.sub);
    return HttpResponse.json({
      firstName: profile?.firstName ?? null,
      lastName: profile?.lastName ?? null,
      phone: profile?.phone ?? null,
      defaultAddress: profile?.defaultAddress ?? null,
    } satisfies Received<CustomerSchemas['ProfileResponse']>);
  }),

  http.put(api('/customers/me/profile'), async ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const body = await readJson(request);
    if (!body) return problem(400, 'Malformed JSON request', {}, 'customer-service');
    const errors: Record<string, string[]> = {};
    for (const field of ['firstName', 'lastName'] as const) {
      const value = body[field];
      if (typeof value !== 'string' || !value.trim()) errors[field] = ['must not be blank'];
      else if (value.length > 100) errors[field] = ['size must be between 0 and 100'];
    }
    if (typeof body.phone !== 'string' || !PHONE.test(body.phone)) {
      errors.phone = ['must match "^\\+[1-9]\\d{7,14}$"'];
    }
    const address = body.defaultAddress;
    if (address != null && (typeof address !== 'string' || address.length > 500)) {
      errors.defaultAddress = ['size must be between 0 and 500'];
    }
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', { errors }, 'customer-service');
    }
    const profile = {
      firstName: String(body.firstName),
      lastName: String(body.lastName),
      phone: String(body.phone),
      defaultAddress: (address as string | null | undefined) ?? null,
    } satisfies Received<CustomerSchemas['ProfileResponse']>;
    db.profiles.set(claims!.sub, profile);
    return HttpResponse.json(profile);
  }),
];

// Order spec (change customer-checkout-orders, design D10).
type OrderSchemas = OrderComponents['schemas'];
const ORDER = 'order-service';
const PAYMENT_WINDOW_MS = 15 * 60_000;
const EXPIRY_GRACE_MS = 30_000;
const TTN_AFTER_MS = 3_000;
const MAX_TOTAL = 9_999_999_999.99;
const CARRIERS = ['NOVA_POSHTA'];

/** Mock knobs; tests set `paymentDelayMs` to 0 for the `…5000` card. */
export const mockSettings = { paymentDelayMs: 2_500 };

function setStock(productId: string, change: number) {
  const product = db.products.find((p) => p.productId === productId);
  if (!product) return;
  product.available += change;
  product.stockStatus = product.available > 0 ? 'IN_STOCK' : 'OUT_OF_STOCK';
}

const release = (order: MockOrder) =>
  order.items.forEach((item) => setStock(item.productId, item.quantity));

/** Background work of Order and Delivery, applied lazily when an order is read. */
function progress(order: MockOrder, now = Date.now()): MockOrder {
  const expires = Date.parse(order.reservationExpiresAt);
  if (order.status === 'PENDING_PAYMENT' && now > expires + EXPIRY_GRACE_MS) {
    order.status = 'EXPIRED';
    release(order);
  }
  if (order.status === 'PAID' && now - Date.parse(order.paidAt!) >= TTN_AFTER_MS) {
    order.status = 'PENDING_SHIPPING';
    order.ttn = `2045${order.orderId.replace(/\D/g, '').padEnd(10, '0').slice(0, 10)}`;
    order.deliveryStatus = 'TTN_GENERATED';
  }
  return order;
}

function ownOrder(claims: MockClaims, id: string): MockOrder | undefined {
  const order = db.orders.find((o) => o.orderId === id && o.customerId === claims.sub);
  return order && progress(order);
}

const orderState = (order: MockOrder, detail: string) =>
  problem(409, detail, { orderStatus: order.status }, ORDER, 'conflict');

/** EP-ORD-01/02 items: 1–100, UUID, quantity 1–10, no duplicates. */
function itemErrors(items: unknown, errors: Record<string, string[]>): CartItem[] | undefined {
  if (!Array.isArray(items) || items.length < 1 || items.length > 100) {
    errors.items = ['size must be between 1 and 100'];
    return undefined;
  }
  items.forEach((item: unknown, index) => {
    const entry = (item ?? {}) as Record<string, unknown>;
    productIdErrors(entry.productId, `items[${index}].productId`, errors);
    quantityErrors(entry.quantity, `items[${index}].quantity`, errors);
    if (typeof entry.quantity === 'number' && entry.quantity > 10) {
      errors[`items[${index}].quantity`] = ['must be less than or equal to 10'];
    }
  });
  if (duplicateProducts(items as CartItem[]).length > 0)
    errors.items = ['must not repeat a product'];
  return Object.keys(errors).length > 0 ? undefined : (items as CartItem[]);
}

function ineligible(items: CartItem[], checkStock: boolean): OrderSchemas['IneligibleItem'][] {
  return items.flatMap<OrderSchemas['IneligibleItem']>(({ productId }) => {
    const product = db.products.find((p) => p.productId === productId);
    if (!product || product.status !== 'ACTIVE') return [{ productId, reason: 'NOT_ACTIVE' }];
    if (checkStock && product.stockStatus === 'OUT_OF_STOCK') {
      return [{ productId, reason: 'INSUFFICIENT_STOCK' }];
    }
    return [];
  });
}

function cardErrors(card: Record<string, unknown> | undefined, now = new Date()) {
  const errors: Record<string, string[]> = {};
  const number = card?.number;
  if (typeof number !== 'string' || !/^\d{13,19}$/.test(number)) {
    errors['cardDetails.number'] = ['must be 13 to 19 digits'];
  }
  const month = card?.expiryMonth;
  const year = card?.expiryYear;
  if (!Number.isInteger(month) || (month as number) < 1 || (month as number) > 12) {
    errors['cardDetails.expiryMonth'] = ['must be between 1 and 12'];
  }
  if (!Number.isInteger(year) || (year as number) < 2000 || (year as number) > 2099) {
    errors['cardDetails.expiryYear'] = ['must be between 2000 and 2099'];
  } else if (
    !errors['cardDetails.expiryMonth'] &&
    (year as number) * 12 + (month as number) < now.getFullYear() * 12 + now.getMonth() + 1
  ) {
    errors['cardDetails.expiryYear'] = ['card has expired'];
  }
  if (typeof card?.cvv !== 'string' || !/^\d{3,4}$/.test(card.cvv)) {
    errors['cardDetails.cvv'] = ['must be 3 or 4 digits'];
  }
  return errors;
}

function placedAnswer(order: MockOrder, replayed: boolean) {
  return HttpResponse.json(
    {
      orderId: order.orderId,
      status: order.status,
      totalAmount: order.totalAmount,
      currency: 'UAH',
      reservationExpiresAt: order.reservationExpiresAt,
    } satisfies OrderSchemas['PlacedOrderResponse'],
    {
      status: 201,
      headers: {
        Location: `/orders/${order.orderId}`,
        ...(replayed ? { 'Idempotent-Replayed': 'true' } : {}),
      },
    },
  );
}

const orderHandlers: HttpHandler[] = [
  // EP-ORD-01: soft check.
  http.post(api('/orders/validate'), async ({ request }) => {
    const { denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const errors: Record<string, string[]> = {};
    const items = itemErrors((await readJson(request))?.items, errors);
    if (!items) return problem(400, 'Validation failed', { errors }, ORDER);
    const ineligibleItems = ineligible(items, true);
    if (ineligibleItems.length > 0) {
      return problem(
        422,
        'Some items cannot be ordered',
        { ineligibleItems } satisfies Partial<OrderSchemas['IneligibleItemsProblem']>,
        ORDER,
        'ineligible-items',
      );
    }
    return new HttpResponse(null, { status: 204 });
  }),

  // EP-ORD-02: place an order with an optional Idempotency-Key (BL-07).
  http.post(api('/orders'), async ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const body = await readJson(request);
    const errors: Record<string, string[]> = {};
    const items = itemErrors(body?.items, errors);
    const delivery = (body?.deliveryDetails ?? {}) as Record<string, unknown>;
    for (const field of ['name', 'phone', 'address', 'carrier']) {
      const value = delivery[field];
      if (typeof value !== 'string' || !value.trim()) {
        errors[`deliveryDetails.${field}`] = ['must not be blank'];
      }
    }
    if (!errors['deliveryDetails.phone'] && !PHONE.test(String(delivery.phone))) {
      errors['deliveryDetails.phone'] = ['must be in international format'];
    }
    if (!errors['deliveryDetails.carrier'] && !CARRIERS.includes(String(delivery.carrier))) {
      errors['deliveryDetails.carrier'] = ['must be one of NOVA_POSHTA'];
    }
    const key = request.headers.get('Idempotency-Key');
    if (key !== null && !UUID.test(key)) errors['Idempotency-Key'] = ['must be a UUID'];
    if (!items || Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', { errors }, ORDER);
    }
    const deliveryDetails = {
      name: String(delivery.name),
      phone: String(delivery.phone),
      address: String(delivery.address),
      carrier: String(delivery.carrier),
    };
    const lines = items.map(({ productId, quantity }) => ({ productId, quantity }));

    const keyId = key && `${claims!.sub} ${key}`;
    const hash = JSON.stringify({ lines, deliveryDetails });
    const known = keyId ? db.idempotencyKeys.get(keyId) : undefined;
    if (known) {
      if (known.hash !== hash) {
        return problem(
          422,
          'Idempotency-Key was used for another request',
          {},
          ORDER,
          'idempotency-key-mismatch',
        );
      }
      return placedAnswer(progress(db.orders.find((o) => o.orderId === known.orderId)!), true);
    }

    const ineligibleItems = ineligible(lines, false);
    if (ineligibleItems.length > 0) {
      return problem(
        422,
        'Some items cannot be ordered',
        { ineligibleItems },
        ORDER,
        'ineligible-items',
      );
    }
    const products = lines.map((line) => db.products.find((p) => p.productId === line.productId)!);
    const insufficientItems = lines
      .map((line, index) => ({ ...line, available: products[index]!.available }))
      .filter((line) => line.quantity > line.available)
      .map(({ productId, quantity, available }) => ({ productId, requested: quantity, available }));
    if (insufficientItems.length > 0) {
      return problem(
        422,
        'Not enough stock',
        { insufficientItems } satisfies Partial<OrderSchemas['InsufficientStockProblem']>,
        ORDER,
        'insufficient-stock',
      );
    }
    const kopecks = lines.reduce(
      (sum, line, index) => sum + Math.round(products[index]!.basePrice * 100) * line.quantity,
      0,
    );
    if (kopecks / 100 > MAX_TOTAL) {
      return problem(
        422,
        'Order total too large',
        { maxTotal: MAX_TOTAL },
        ORDER,
        'order-total-too-large',
      );
    }

    lines.forEach((line) => setStock(line.productId, -line.quantity));
    const now = Date.now();
    const order: MockOrder = {
      orderId: crypto.randomUUID(),
      customerId: claims!.sub,
      status: 'PENDING_PAYMENT',
      items: lines.map((line, index) => ({
        productId: line.productId,
        productTitle: products[index]!.title,
        unitPriceAtOrder: products[index]!.basePrice,
        quantity: line.quantity,
      })),
      deliveryDetails,
      totalAmount: kopecks / 100,
      placedAt: new Date(now).toISOString(),
      reservationExpiresAt: new Date(now + PAYMENT_WINDOW_MS).toISOString(),
    };
    db.orders.push(order);
    if (keyId) db.idempotencyKeys.set(keyId, { hash, orderId: order.orderId });
    return placedAnswer(order, false);
  }),

  // EP-ORD-03: pay, by the demo payment provider's test cards.
  http.post(api('/orders/:orderId/pay'), async ({ request, params }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const card = (await readJson(request))?.cardDetails as Record<string, unknown> | undefined;
    const errors = cardErrors(card);
    if (Object.keys(errors).length > 0) return problem(400, 'Validation failed', { errors }, ORDER);
    const order = ownOrder(claims!, String(params.orderId));
    if (!order) return problem(404, 'Order not found', {}, ORDER);
    if (order.status !== 'PENDING_PAYMENT')
      return orderState(order, 'Order is not awaiting payment');
    if (Date.parse(order.reservationExpiresAt) <= Date.now()) {
      return problem(409, 'Payment window expired', {}, ORDER, 'payment-window-expired');
    }
    const last4 = String(card!.number).slice(-4);
    if (last4 === '5000') await delay(mockSettings.paymentDelayMs);
    else if (last4 !== '4242') {
      const reason = last4 === '0002' ? 'insufficient_funds' : 'card_declined';
      return problem(
        402,
        'Payment declined',
        { reason } satisfies Partial<OrderSchemas['PaymentDeclinedProblem']>,
        ORDER,
        'payment-declined',
      );
    }
    order.status = 'PAID';
    order.paidAt = new Date().toISOString();
    // Customer EP-CUST-EVT-01: the ordered products leave the cart.
    const ordered = new Set(order.items.map((item) => item.productId));
    const cart = db.carts.get(order.customerId);
    if (cart)
      db.carts.set(
        order.customerId,
        cart.filter((line) => !ordered.has(line.productId)),
      );
    return HttpResponse.json({
      orderId: order.orderId,
      status: order.status,
    } satisfies OrderSchemas['PaidOrderResponse']);
  }),

  // EP-ORD-04: history, newest first.
  http.get(api('/orders'), ({ request }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const params = new URL(request.url).searchParams;
    const page = intParam(params.get('page'), 0);
    const size = intParam(params.get('size'), 20);
    if (page === undefined || size === undefined || size < 1 || size > 100) {
      return problem(400, 'Invalid request parameters', {}, ORDER);
    }
    const own = db.orders
      .filter((o) => o.customerId === claims!.sub)
      .map((o) => progress(o))
      .sort((a, b) => b.placedAt.localeCompare(a.placedAt));
    return HttpResponse.json({
      content: own.slice(page * size, page * size + size).map((o) => ({
        orderId: o.orderId,
        status: o.status,
        totalAmount: o.totalAmount,
        currency: 'UAH',
        placedAt: o.placedAt,
      })),
      page,
      size,
      totalElements: own.length,
    } satisfies OrderSchemas['PageResponseOrderSummaryResponse']);
  }),

  // EP-ORD-05: details; `404` for another customer's order.
  http.get(api('/orders/:orderId'), ({ request, params }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const order = ownOrder(claims!, String(params.orderId));
    if (!order) return problem(404, 'Order not found', {}, ORDER);
    return HttpResponse.json({
      orderId: order.orderId,
      status: order.status,
      items: order.items,
      deliveryDetails: order.deliveryDetails,
      totalAmount: order.totalAmount,
      currency: 'UAH',
      placedAt: order.placedAt,
      reservationExpiresAt: order.reservationExpiresAt,
      ...(order.paidAt ? { paidAt: order.paidAt } : {}),
      ...(order.ttn ? { ttn: order.ttn } : {}),
      ...(order.deliveryStatus ? { deliveryStatus: order.deliveryStatus } : {}),
    } satisfies OrderSchemas['OrderDetailsResponse']);
  }),

  // EP-ORD-06: cancel; the saga finishes at once in the mock.
  http.delete(api('/orders/:orderId'), ({ request, params }) => {
    const { claims, denied } = authenticate(request, CUSTOMER_ONLY);
    if (denied) return denied;
    const order = ownOrder(claims!, String(params.orderId));
    if (!order) return problem(404, 'Order not found', {}, ORDER);
    if (order.status === 'CANCELLATION_REQUESTED') {
      return orderState(order, 'Cancellation already in progress');
    }
    if (!['PENDING_PAYMENT', 'PAID', 'PENDING_SHIPPING'].includes(order.status)) {
      return orderState(order, 'Order cannot be cancelled in its current state');
    }
    release(order);
    order.status = 'CANCELLED';
    return HttpResponse.json(
      {
        orderId: order.orderId,
        status: order.status,
      } satisfies OrderSchemas['CancellationResponse'],
      { status: 202 },
    );
  }),
];

export const handlers: HttpHandler[] = [
  ...cartHandlers,
  ...profileHandlers,
  ...orderHandlers,

  // Auth spec EP-AUTH-01: register a customer.
  http.post(api('/user/register'), async ({ request }) => {
    const body = await readJson(request);
    if (!body) return problem(400, 'Malformed JSON request');
    const errors = credentialErrors(body);
    if (Object.keys(errors).length > 0) return problem(400, 'Validation failed', { errors });
    const user = createUser(String(body.email), String(body.password), 'CUSTOMER');
    if (!user) return problem(409, 'Email already in use', {}, 'auth-service');
    return HttpResponse.json({ userId: user.id } satisfies AuthSchemas['RegisterUserResponse'], {
      status: 201,
    });
  }),

  // Auth spec EP-AUTH-02: log in.
  http.post(api('/auth/login'), async ({ request }) => {
    const body = await readJson(request);
    const errors: Record<string, string[]> = {};
    if (typeof body?.email !== 'string' || !body.email.trim()) errors.email = ['must not be blank'];
    if (typeof body?.password !== 'string' || !body.password.trim()) {
      errors.password = ['must not be blank'];
    }
    if (Object.keys(errors).length > 0) return problem(400, 'Validation failed', { errors });
    const email = String(body?.email).trim().toLowerCase();
    const user = db.users.find((u) => u.email === email && u.password === body?.password);
    if (!user) return problem(401, 'Invalid credentials', {}, 'auth-service');
    return HttpResponse.json({
      token: createMockToken(user),
    } satisfies AuthSchemas['LoginResponse']);
  }),

  // Auth spec EP-AUTH-03: create a staff or admin account (gateway: Required, ADMIN).
  http.post(api('/user/non-customer'), async ({ request }) => {
    const { denied } = authenticate(request, { required: ['ADMIN'] });
    if (denied) return denied;
    const body = await readJson(request);
    const errors = credentialErrors(body);
    const role = body?.role;
    if (role === 'CUSTOMER') errors.role = ['must not be CUSTOMER'];
    else if (!['CATALOG_MANAGER', 'INVENTORY_WORKER', 'ADMIN'].includes(String(role))) {
      errors.role = ['must be a known role'];
    }
    if (Object.keys(errors).length > 0) return problem(400, 'Validation failed', { errors });
    const user = createUser(String(body?.email), String(body?.password), role as Role);
    if (!user) return problem(409, 'Email already in use', {}, 'auth-service');
    return HttpResponse.json({ userId: user.id } satisfies AuthSchemas['RegisterUserResponse'], {
      status: 201,
    });
  }),

  // Catalog spec EP-CAT-04: browse products (gateway: Optional).
  http.get(api('/catalog/products'), ({ request }) => {
    const { claims, denied } = authenticate(request, 'optional');
    if (denied) return denied;
    const params = new URL(request.url).searchParams;
    const errors: Record<string, string[]> = {};

    const page = intParam(params.get('page'), 0);
    const size = intParam(params.get('size'), 20);
    if (page === undefined) errors.page = ['must be a non-negative integer'];
    if (size === undefined || size < 1 || size > 100) errors.size = ['must be between 1 and 100'];
    const minPrice = priceParam(params.get('minPrice'));
    const maxPrice = priceParam(params.get('maxPrice'));
    if (minPrice === undefined) errors.minPrice = ['must be a number'];
    if (maxPrice === undefined) errors.maxPrice = ['must be a number'];
    const sortBy = params.get('sortBy') ?? 'title';
    const sortMatch = /^(title|basePrice)(?:,(asc|desc))?$/i.exec(sortBy);
    if (!sortMatch) errors.sortBy = ['must be title or basePrice, optionally with ,asc or ,desc'];
    const status = params.get('status');
    if (status !== null && status !== 'ACTIVE' && status !== 'NOT_ACTIVE') {
      errors.status = ['must be ACTIVE or NOT_ACTIVE'];
    }
    // Catalog task 28: `ids` filter, at most 100 distinct UUIDs.
    const ids = params.get('ids') ? new Set(params.get('ids')!.split(',')) : undefined;
    if (ids && (ids.size > 100 || [...ids].some((id) => !UUID.test(id)))) {
      errors.ids = ['must be at most 100 product IDs'];
    }
    if (Object.keys(errors).length > 0)
      return problem(400, 'Invalid request parameters', { errors });
    if (typeof minPrice === 'number' && typeof maxPrice === 'number' && minPrice > maxPrice) {
      return problem(400, 'minPrice must not be greater than maxPrice', {}, 'catalog-service');
    }

    // Catalog task 27: managers and inventory workers see every status.
    const isManager = claims?.role === 'CATALOG_MANAGER' || claims?.role === 'INVENTORY_WORKER';
    const search = params.get('search')?.trim().toLowerCase();
    const categoryId = params.get('categoryId');
    const field = sortMatch![1] as 'title' | 'basePrice';
    const direction = sortMatch![2]?.toLowerCase() === 'desc' ? -1 : 1;

    const matches = db.products
      .filter((p) =>
        isManager ? !status || p.status === (status as ProductStatus) : p.status === 'ACTIVE',
      )
      .filter(
        (p) =>
          !search ||
          p.title.toLowerCase().includes(search) ||
          p.description.toLowerCase().includes(search),
      )
      .filter((p) => !categoryId || p.categoryId === categoryId)
      .filter((p) => !ids || ids.has(p.productId))
      .filter((p) => typeof minPrice !== 'number' || p.basePrice >= minPrice)
      .filter((p) => typeof maxPrice !== 'number' || p.basePrice <= maxPrice)
      .sort((a, b) => {
        const order = field === 'title' ? compareText(a.title, b.title) : a.basePrice - b.basePrice;
        return order * direction || compareText(a.productId, b.productId);
      });

    const start = page! * size!;
    return HttpResponse.json({
      content: matches.slice(start, start + size!).map((p) => ({
        productId: p.productId,
        title: p.title,
        basePrice: p.basePrice,
        categoryId: p.categoryId,
        status: p.status,
        stockStatus: p.stockStatus,
      })),
      page: page!,
      size: size!,
      totalElements: matches.length,
    } satisfies CatalogSchemas['PageResponseProductSummaryResponse']);
  }),

  // Catalog spec EP-CAT-05: product details (gateway: Public; non-UUID ids have no route).
  http.get(api('/catalog/products/:productId'), ({ params }) => {
    const id = String(params.productId);
    if (!UUID.test(id)) return problem(404, 'No route matches the request.', {}, 'api-gateway');
    const product = db.products.find((p) => p.productId === id && p.status === 'ACTIVE');
    if (!product) return problem(404, 'Product not found', {}, 'catalog-service');
    return HttpResponse.json({
      productId: product.productId,
      title: product.title,
      description: product.description,
      categoryId: product.categoryId,
      basePrice: product.basePrice,
      status: product.status,
      stockStatus: product.stockStatus,
    } satisfies CatalogSchemas['ProductResponse']);
  }),

  // Catalog spec EP-CAT-10: categories ordered by name (gateway: Public).
  http.get(api('/catalog/categories'), ({ request }) => {
    const params = new URL(request.url).searchParams;
    const page = intParam(params.get('page'), 0);
    const size = intParam(params.get('size'), 20);
    if (page === undefined || size === undefined || size < 1 || size > 100) {
      return problem(400, 'Invalid request parameters');
    }
    const search = params.get('search')?.toLowerCase();
    const matches = db.categories
      .filter((c) => !search || c.name.toLowerCase().includes(search))
      .sort((a, b) => compareText(a.name, b.name) || compareText(a.categoryId, b.categoryId));
    return HttpResponse.json({
      content: matches.slice(page * size, page * size + size),
      page,
      size,
      totalElements: matches.length,
    } satisfies CatalogSchemas['PageResponseCategoryResponse']);
  }),

  // Gateway: anything else has no route.
  http.all(api('/*'), () => problem(404, 'No route matches the request.', {}, 'api-gateway')),
];
