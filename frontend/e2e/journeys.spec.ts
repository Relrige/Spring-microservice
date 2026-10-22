import { expect, test } from '@playwright/test';
import { DEMO_PRODUCTS, DEMO_STAFF } from '../src/fixtures/demo-data';
import { adminAccount, expectNoHorizontalScroll, logIn, shortenSession } from './support';

const ACTIVE = DEMO_PRODUCTS.filter((p) => p.active);
const PHONES = ACTIVE.filter((p) => p.category === 'Phones');
const manager = DEMO_STAFF.find((a) => a.role === 'CATALOG_MANAGER')!;
const worker = DEMO_STAFF.find((a) => a.role === 'INVENTORY_WORKER')!;

test('browse, filter, page and open a product', async ({ page }) => {
  await page.goto('/');
  const tiles = page.getByTestId('product-tile');
  await expect(tiles).toHaveCount(20);
  // A live stack may hold products besides the fixtures, so the total is at least the fixtures.
  const total = Number((await page.getByText(/^\d+ products$/).textContent())!.split(' ')[0]);
  expect(total).toBeGreaterThanOrEqual(ACTIVE.length);

  await page.getByRole('button', { name: 'Next' }).click();
  await expect(page).toHaveURL(/\?page=2$/);
  await expect(page.getByRole('button', { name: 'Page 2' })).toHaveAttribute(
    'aria-current',
    'page',
  );

  await page.getByRole('radio', { name: 'Phones', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Phones' })).toBeVisible();
  await expect(tiles).toHaveCount(PHONES.length);
  await expect(page).not.toHaveURL(/page=/);

  await page.getByLabel('Sort by').selectOption({ label: 'Price high to low' });
  const priciest = [...PHONES].sort((a, b) => b.price - a.price)[0]!;
  await expect(tiles.first()).toContainText(priciest.title);

  await page.goBack();
  await expect(page.getByLabel('Sort by')).toHaveValue('title,asc');

  await tiles.first().click();
  await expect(page).toHaveURL(/\/products\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByTestId('product-category')).toHaveText('Phones');
  await expect(page.getByText(/^UAH /).first()).toBeVisible();
});

test('register, log out, log in again', async ({ page }) => {
  const email = `e2e-${Date.now()}@example.com`;
  await page.goto('/register');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('secret-123');
  await page.getByLabel('Confirm password').fill('secret-123');
  await page.getByRole('button', { name: 'Create account' }).click();

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('current-user')).toHaveText(`${email} · Customer`);

  await page.getByRole('button', { name: 'Log out' }).click();
  // The header link: the register page, with its own "Log in" link, may still be leaving.
  const headerLogIn = page.getByRole('banner').getByRole('link', { name: 'Log in' });
  await expect(headerLogIn).toBeVisible();

  await headerLogIn.click();
  await logIn(page, email, 'secret-123');
  await expect(page.getByTestId('current-user')).toHaveText(`${email} · Customer`);

  await page.reload();
  await expect(page.getByTestId('current-user')).toHaveText(`${email} · Customer`);
});

test('staff login lands in its area', async ({ page }) => {
  const cases = [
    { account: manager, path: '/manage/products', heading: 'Products' },
    { account: worker, path: '/warehouse', heading: 'Warehouse' },
    { account: adminAccount(), path: '/admin', heading: 'Administration' },
  ];
  for (const { account, path, heading } of cases) {
    await page.goto('/login');
    await logIn(page, account.email, account.password);
    await expect(page).toHaveURL(new RegExp(`${path}$`));
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page.getByRole('link', { name: 'Log in' })).toBeVisible();
  }
});

test('expired session returns to the same page after login', async ({ page }) => {
  await page.goto('/login');
  await logIn(page, manager.email, manager.password);
  await expect(page).toHaveURL(/\/manage\/products$/);

  await shortenSession(page, 2);
  await page.reload();
  await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();

  await expect(page.getByText('Your session has expired.', { exact: false })).toBeVisible();
  await expect(page).toHaveURL(/\/login\?expired=1&returnTo=%2Fmanage%2Fproducts$/);

  await logIn(page, manager.email, manager.password);
  await expect(page).toHaveURL(/\/manage\/products$/);
});

test('layout at 375 px has no horizontal scroll', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto('/');
  await expect(page.getByTestId('product-tile').first()).toBeVisible();
  await expectNoHorizontalScroll(page);

  await page.getByRole('button', { name: 'Open menu' }).click();
  await expect(
    page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Catalog' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Close menu' }).click();

  await page.getByTestId('product-tile').first().click();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expectNoHorizontalScroll(page);

  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Log in' })).toBeVisible();
  await expectNoHorizontalScroll(page);
});
