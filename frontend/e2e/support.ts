import { expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  DEMO_ADMIN,
  DEMO_CART,
  DEMO_CUSTOMER,
  DEMO_PROFILE,
  DEMO_STAFF,
  type DemoAccount,
} from '../src/fixtures/demo-data';

export const SESSION_KEY = 'voltstore.session';

/**
 * The admin's password: the mock uses the fixture value; a live stack uses
 * its ADMIN_PASSWORD, given to the tests as E2E_ADMIN_PASSWORD (or SEED_ADMIN_PASSWORD).
 */
export function adminAccount(): DemoAccount {
  const password = process.env.E2E_ADMIN_PASSWORD ?? process.env.SEED_ADMIN_PASSWORD;
  return password ? { ...DEMO_ADMIN, password } : DEMO_ADMIN;
}

export async function logIn(page: Page, email: string, password: string) {
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
}

export async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

/** Replaces the stored token's expiry so the session ends in `seconds` (the app reads exp only). */
export async function shortenSession(page: Page, seconds: number) {
  await page.evaluate(
    ({ key, seconds }) => {
      const token = window.localStorage.getItem(key);
      if (!token) throw new Error('no session');
      const [header, payload] = token.split('.');
      const decode = (part: string) =>
        JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/'))) as Record<string, unknown>;
      const encode = (value: unknown) =>
        btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
      const claims = decode(payload!);
      claims.exp = Math.floor(Date.now() / 1000) + seconds;
      window.localStorage.setItem(key, `${header}.${encode(claims)}.unsigned`);
    },
    { key: SESSION_KEY, seconds },
  );
}

async function apiToken(request: APIRequestContext, account: DemoAccount): Promise<string> {
  const answer = await request.post('/api/auth/login', {
    data: { email: account.email, password: account.password },
  });
  expect(answer.ok(), `log in as ${account.email}`).toBe(true);
  return ((await answer.json()) as { token: string }).token;
}

/**
 * Live mode only (change customer-cart, design D11): puts DEMO_CART back into the
 * demo customer's cart through the gateway, so earlier runs do not change the
 * result. The mock starts from the fixtures on every page load.
 */
export async function resetDemoCart(request: APIRequestContext) {
  const manager = await apiToken(
    request,
    DEMO_STAFF.find((a) => a.role === 'CATALOG_MANAGER')!,
  );
  const items = [];
  for (const line of DEMO_CART) {
    // The manager also sees the inactive product of the demo cart.
    const found = await request.get('/api/catalog/products', {
      params: { search: line.title, size: 100 },
      headers: { Authorization: `Bearer ${manager}` },
    });
    const { content } = (await found.json()) as { content: { productId: string; title: string }[] };
    const product = content.find((p) => p.title === line.title);
    expect(product, `demo product ${line.title}`).toBeDefined();
    items.push({ productId: product!.productId, quantity: line.quantity });
  }
  const customer = await apiToken(request, DEMO_CUSTOMER);
  const answer = await request.put('/api/customers/cart', {
    headers: { Authorization: `Bearer ${customer}` },
    data: { items },
  });
  expect(answer.ok(), 'reset the demo cart').toBe(true);
}

/** Logs in as the demo customer and goes from the cart to the payment step of a new order. */
export async function placeOrder(page: Page): Promise<string> {
  await page.goto('/login');
  await logIn(page, DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
  await expect(page.getByTestId('current-user')).toContainText(DEMO_CUSTOMER.email);

  await page.getByRole('link', { name: /^Cart, \d+ items?$/ }).click();
  const gone = page.getByTestId('cart-line').filter({ hasText: 'Product no longer available' });
  await expect(gone).toBeVisible();
  await expect(page.getByRole('button', { name: 'Proceed to checkout' })).toBeDisabled();
  await gone.getByRole('button', { name: 'Remove' }).click();
  await expect(gone).toHaveCount(0);

  await page.getByRole('button', { name: 'Proceed to checkout' }).click();
  await expect(page).toHaveURL(/\/checkout$/);
  await expect(page.getByLabel('Recipient')).toHaveValue(
    `${DEMO_PROFILE.firstName} ${DEMO_PROFILE.lastName}`,
  );
  await page.getByRole('button', { name: 'Place order' }).click();
  await expect(page).toHaveURL(/\/checkout\/[0-9a-f-]{36}$/);
  return page.url().split('/').pop()!;
}

export async function pay(page: Page, number: string) {
  await page.getByLabel('Card number').fill(number);
  await page.getByLabel('Expiry (MM/YY)').fill('12/30');
  await page.getByLabel('CVV').fill('123');
  await page.getByRole('button', { name: /^Pay UAH/ }).click();
}
