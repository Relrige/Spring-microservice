import { expect, test, type Page } from '@playwright/test';
import { DEMO_CUSTOMER } from '../src/fixtures/demo-data';
import { logIn, resetDemoCart } from './support';

const SONY = 'Sony WH-1000XM5 headphones';
const MARSHALL = 'Marshall Emberton III speaker';

test.beforeEach(async ({ request }, testInfo) => {
  if (testInfo.project.name === 'live') await resetDemoCart(request);
});

async function addFromProductPage(page: Page, title: string, quantity: number) {
  await page.goto('/');
  await page.getByLabel('Search products').fill(title);
  await page.getByLabel('Search products').press('Enter');
  await page.getByTestId('product-tile').filter({ hasText: title }).click();
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  await page.getByLabel('Quantity').fill(String(quantity));
  await page.getByRole('button', { name: 'Add to cart' }).click();
  await expect(page.getByText('Added to cart')).toBeVisible();
}

const cartLine = (page: Page, title: string) =>
  page.getByTestId('cart-line').filter({ hasText: title });

test('guest cart: add two products, badge, cart page, kept after reload', async ({ page }) => {
  await addFromProductPage(page, SONY, 1);
  await addFromProductPage(page, MARSHALL, 2);
  await expect(page.getByRole('link', { name: 'Cart, 3 items' })).toBeVisible();

  await page.getByRole('link', { name: 'Cart, 3 items' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Cart' })).toBeVisible();
  await expect(page.getByTestId('cart-line')).toHaveCount(2);
  await expect(cartLine(page, MARSHALL).getByTestId('line-quantity')).toHaveText('2');
  await expect(page.getByTestId('summary-items')).toHaveText('3');

  await page.reload();
  await expect(page.getByTestId('cart-line')).toHaveCount(2);
  await expect(page.getByRole('link', { name: 'Cart, 3 items' })).toBeVisible();
});

test('login as the demo customer, merge both carts, logout to an empty cart', async ({ page }) => {
  await addFromProductPage(page, SONY, 2);
  await addFromProductPage(page, MARSHALL, 1);

  await page.goto('/login');
  await logIn(page, DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
  const dialog = page.getByRole('dialog', { name: 'You have products in two carts' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('group', { name: 'In this browser' })).toContainText(`2 × ${SONY}`);
  await expect(dialog.getByRole('group', { name: 'In your account' })).toContainText(`1 × ${SONY}`);
  await dialog.getByRole('button', { name: 'Merge both' }).click();
  await expect(dialog).toBeHidden();

  // Demo cart 1 Sony, 2 Anker, 1 inactive Xiaomi + guest 2 Sony, 1 Marshall.
  await page.getByRole('link', { name: 'Cart, 7 items' }).click();
  await expect(page.getByTestId('cart-line')).toHaveCount(4);
  await expect(cartLine(page, SONY).getByTestId('line-quantity')).toHaveText('3');
  await expect(cartLine(page, 'Product no longer available')).toBeVisible();

  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page.getByRole('link', { name: 'Log in' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Cart', exact: true })).toBeVisible();
  await page.goto('/cart');
  await expect(page.getByText('Your cart is empty')).toBeVisible();
});
