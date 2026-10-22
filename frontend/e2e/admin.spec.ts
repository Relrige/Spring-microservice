import { expect, test } from '@playwright/test';
import { adminAccount, logIn } from './support';

// Change admin-staff-accounts, design D3. One page session, so the mock keeps the new account.
test('admin creates an inventory worker who can log in', async ({ page }) => {
  const email = `e2e-worker-${Date.now()}@example.com`;
  const admin = adminAccount();
  await page.goto('/login');
  await logIn(page, admin.email, admin.password);
  await expect(page.getByRole('heading', { level: 1, name: 'Staff accounts' })).toBeVisible();

  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('worker-123');
  await page.getByLabel('Confirm password').fill('worker-123');
  await page.getByRole('combobox', { name: 'Role' }).click();
  await page.getByRole('option', { name: 'Inventory worker' }).click();
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByText(`Account created: ${email} · Inventory worker`)).toBeVisible();

  await page.getByRole('button', { name: 'Log out' }).click();
  await page.getByRole('link', { name: 'Log in' }).click();
  await logIn(page, email, 'worker-123');
  await expect(page.getByRole('heading', { level: 1, name: 'Stock' })).toBeVisible();
});
