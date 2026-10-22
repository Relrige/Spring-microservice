import { expect, test, type Page } from '@playwright/test';
import { DEMO_CUSTOMER, DEMO_PROFILE } from '../src/fixtures/demo-data';
import { logIn, resetDemoCart } from './support';

// Change customer-checkout-orders, design D12. The mock keeps its data in the page,
// so the journeys move between pages by links, not by reloading.

test.beforeEach(async ({ request }, testInfo) => {
  if (testInfo.project.name === 'live') await resetDemoCart(request);
});

/** Logs in as the demo customer and goes from the cart to the payment step of a new order. */
async function placeOrder(page: Page): Promise<string> {
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

async function pay(page: Page, number: string) {
  await page.getByLabel('Card number').fill(number);
  await page.getByLabel('Expiry (MM/YY)').fill('12/30');
  await page.getByLabel('CVV').fill('123');
  await page.getByRole('button', { name: /^Pay UAH/ }).click();
}

test('checkout: decline, then pay, and find the order in the history', async ({ page }) => {
  const orderId = await placeOrder(page);
  await expect(page.getByText(/is placed and the items are reserved for you/)).toBeVisible();

  await pay(page, '4000 0000 0000 0002');
  await expect(page.getByRole('alert')).toContainText(
    'This card was declined: insufficient funds.',
  );

  await pay(page, '4242 4242 4242 4242');
  await expect(page).toHaveURL(new RegExp(`/account/orders/${orderId}$`));
  await expect(page.getByText('Payment received')).toBeVisible();
  await expect(
    page.getByRole('heading', { level: 1, name: `Order #${orderId.slice(0, 8)}` }),
  ).toBeVisible();
  await expect(page.getByText(/^(Paid|Ready to ship)$/).first()).toBeVisible();

  await page.getByRole('link', { name: '← Your orders' }).click();
  await expect(page.getByTestId('order-row').first()).toContainText(`#${orderId.slice(0, 8)}`);
});

test('cancel an unpaid order from the order page', async ({ page }) => {
  const orderId = await placeOrder(page);
  await page
    .getByRole('navigation', { name: 'Main' })
    .getByRole('link', { name: 'Orders' })
    .click();
  await page
    .getByTestId('order-row')
    .filter({ hasText: `#${orderId.slice(0, 8)}` })
    .click();
  await expect(page.getByText('Awaiting payment').first()).toBeVisible();

  await page.getByRole('button', { name: 'Cancel order' }).click();
  await page
    .getByRole('dialog', { name: 'Cancel this order?' })
    .getByRole('button', { name: 'Cancel order' })
    .click();
  await expect(page.getByText('Cancelled').first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel order' })).toHaveCount(0);
});
