import { expect, test } from '@playwright/test';
import { DEMO_PRODUCTS, DEMO_STAFF } from '../src/fixtures/demo-data';
import { logIn, pay, placeOrder, resetDemoCart } from './support';

// Change inventory-worker-console, design D9: one page session, so the mock keeps
// the order between the customer and the worker.

const worker = DEMO_STAFF.find((a) => a.role === 'INVENTORY_WORKER')!;
const SONY = DEMO_PRODUCTS.find((p) => p.title === 'Sony WH-1000XM5 headphones')!;

test.beforeEach(async ({ request }, testInfo) => {
  if (testInfo.project.name === 'live') await resetDemoCart(request);
});

test('worker ships a paid order and tends the stock', async ({ page }) => {
  // The customer pays an order.
  const orderId = await placeOrder(page);
  await pay(page, '4242 4242 4242 4242');
  await expect(page).toHaveURL(new RegExp(`/account/orders/${orderId}$`));
  await page.getByRole('button', { name: 'Log out' }).click();

  // The worker ships it once its TTN is ready.
  await page.getByRole('link', { name: 'Log in' }).click();
  await logIn(page, worker.email, worker.password);
  await expect(page.getByRole('heading', { level: 1, name: 'Stock' })).toBeVisible();
  await page.getByRole('link', { name: /^Fulfillment queue/ }).click();
  const order = page.getByTestId('queue-order').filter({ hasText: `#${orderId.slice(0, 8)}` });
  await expect(order.getByText('TTN ready')).toBeVisible({ timeout: 30_000 });
  await order.getByRole('button', { name: 'Mark as shipped' }).click();
  await page
    .getByRole('dialog', { name: 'Mark as shipped?' })
    .getByRole('button', { name: 'Mark as shipped' })
    .click();
  await expect(page.getByText('Order shipped')).toBeVisible();
  await expect(order).toHaveCount(0);

  // Stock: restock 1 and write off 1 (live stock stays as seeded), then set packaging.
  await page.getByRole('link', { name: 'Stock', exact: true }).click();
  await page.getByLabel('Search by title').fill(SONY.title);
  await page.getByRole('button', { name: 'Search' }).click();
  const row = page.getByTestId('stock-row').filter({ hasText: SONY.title });
  await expect(row).toHaveCount(1);
  const before = Number(await row.getByTestId('available').textContent());

  for (const [mode, change] of [
    ['Restock', 1],
    ['Write off', -1],
  ] as const) {
    await row.getByRole('button', { name: 'Adjust' }).click();
    const dialog = page.getByRole('dialog', { name: 'Adjust stock' });
    await dialog.getByText(mode).click();
    await dialog.getByLabel('Quantity').fill('1');
    await dialog.getByRole('button', { name: 'Save' }).click();
    await expect(dialog).toBeHidden();
    await expect(row.getByTestId('available')).toHaveText(String(before + (change === 1 ? 1 : 0)));
  }

  await row.getByRole('button', { name: 'Set packaging' }).click();
  const packaging = page.getByRole('dialog', { name: 'Set packaging' });
  await packaging.getByLabel('Gross weight, g').fill(String(SONY.packaging.grossWeightGrams));
  await packaging.getByLabel('Length, cm').fill(String(SONY.packaging.lengthCm));
  await packaging.getByLabel('Width, cm').fill(String(SONY.packaging.widthCm));
  await packaging.getByLabel('Height, cm').fill(String(SONY.packaging.heightCm));
  await packaging.getByRole('button', { name: 'Save packaging' }).click();
  await expect(packaging).toBeHidden();
  await expect(row.getByText('Missing')).toHaveCount(0);
});
