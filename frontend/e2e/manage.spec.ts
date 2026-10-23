// Catalog manager journeys (change catalog-manager-console, design D11), mock and live.
// The mock keeps its data in memory and starts again from the fixtures on every page load,
// so after a change the journeys move between pages inside the app; only the live run
// reloads to prove that a value came back from the server.
import { expect, test, type Page } from '@playwright/test';
import { DEMO_STAFF } from '../src/fixtures/demo-data';
import { expectNoHorizontalScroll, logIn } from './support';

const manager = DEMO_STAFF.find((a) => a.role === 'CATALOG_MANAGER')!;
const DRAFT = 'E2E draft product';
const ROUTER = 'TP-Link Archer AX55 Wi-Fi 6 router';
const REJECTIONS =
  /This product cannot be activated yet: (its packaging size and weight are not set|the warehouse has no stock record for it)/;

async function logInAsManager(page: Page) {
  await page.goto('/login');
  await logIn(page, manager.email, manager.password);
  await expect(page).toHaveURL(/\/manage\/products$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();
}

const main = (page: Page) => page.getByRole('navigation', { name: 'Main' });

/** The manager list filtered by a title, reached through the header link. */
async function findInManagerList(page: Page, title: string) {
  await main(page).getByRole('link', { name: 'Products' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Products' })).toBeVisible();
  const search = page.getByRole('searchbox', { name: 'Search products' });
  await search.fill(title);
  await search.press('Enter');
  await expect(page).toHaveURL(/\/manage\/products\?search=/);
}

function managerRow(page: Page, title: string) {
  return page.getByTestId('product-row').filter({
    has: page.getByRole('link', { name: title, exact: true }),
  });
}

/** The storefront search through the header, as a shopper would do it. */
async function searchStorefront(page: Page, title: string) {
  await main(page).getByRole('link', { name: 'Catalog' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'All products' })).toBeVisible();
  await page.getByRole('searchbox', { name: 'Search products' }).fill(title);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(
    page.getByRole('heading', { level: 1, name: `Results for “${title}”` }),
  ).toBeVisible();
}

test('manager creates, renames and deletes a category', async ({ page }) => {
  const name = `E2E category ${Date.now()}`;
  await logInAsManager(page);
  await main(page).getByRole('link', { name: 'Categories' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Categories' })).toBeVisible();

  await page.getByRole('button', { name: 'New category' }).click();
  const create = page.getByRole('dialog', { name: 'New category' });
  await create.getByLabel('Name').fill(name);
  await create.getByRole('button', { name: 'Create category' }).click();
  await expect(page.getByText('Category created')).toBeVisible();

  const search = page.getByRole('searchbox', { name: 'Search categories' });
  await search.fill(name);
  await search.press('Enter');
  const row = page.getByTestId('category-row').filter({ hasText: name });
  await expect(row).toHaveCount(1);

  await row.getByRole('button', { name: 'Rename' }).click();
  const rename = page.getByRole('dialog', { name: 'Rename category' });
  await rename.getByLabel('Name').fill(`${name} renamed`);
  await rename.getByRole('button', { name: 'Rename' }).click();
  await expect(page.getByText('Category renamed')).toBeVisible();
  const renamed = page.getByTestId('category-row').filter({ hasText: `${name} renamed` });
  await expect(renamed).toHaveCount(1);

  await renamed.getByRole('button', { name: 'Delete' }).click();
  const confirm = page.getByRole('dialog', { name: `Delete category “${name} renamed”?` });
  await confirm.getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('Category deleted')).toBeVisible();
  await expect(page.getByText('No categories match your search')).toBeVisible();
});

test('manager prepares a draft product: activation rejected, edit saved and loaded again', async ({
  page,
}, testInfo) => {
  const live = testInfo.project.name === 'live';
  await logInAsManager(page);
  await findInManagerList(page, DRAFT);

  // Live: created once and reused by later runs (Catalog has no hard delete). Mock: every run.
  const existing = managerRow(page, DRAFT);
  // Wait for the filtered answer: the draft's own row, or none (the unfiltered list may show first).
  await expect(existing.or(page.getByText('No products match')).first()).toBeVisible();
  if ((await existing.count()) > 0) {
    await existing.first().getByRole('link', { name: DRAFT }).click();
  } else {
    await page.getByRole('link', { name: 'New product' }).click();
    await page.getByLabel('Title').fill(DRAFT);
    await page.getByLabel('Description').fill('A product the browser tests prepare.');
    await page.getByPlaceholder('Choose a category').click();
    await page.getByRole('option', { name: 'Accessories', exact: true }).click();
    await page.getByLabel('Price').fill('199');
    await page.getByRole('button', { name: 'Create product' }).click();
    await expect(
      page.getByText('Product created. It stays inactive until you activate it.'),
    ).toBeVisible();
  }
  await expect(page).toHaveURL(/\/manage\/products\/[0-9a-f-]{36}$/);
  const panel = page.getByRole('complementary', { name: 'Product status' });
  await expect(panel.getByText('Inactive')).toBeVisible();

  // A just-created live product may not have its stock record yet: either reason is fine.
  await panel.getByRole('button', { name: 'Activate', exact: true }).click();
  await expect(panel.getByRole('alert')).toContainText(REJECTIONS);
  await expect(panel.getByText('Inactive')).toBeVisible();

  const description = `A product the browser tests prepare. Saved at ${Date.now()}.`;
  await page.getByLabel('Description').fill(description);
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByText('Changes saved')).toBeVisible();

  // BL-01 end to end: the inactive product loads again with its description.
  if (live) await page.reload();
  else {
    await findInManagerList(page, DRAFT);
    await managerRow(page, DRAFT).getByRole('link', { name: DRAFT }).click();
  }
  await expect(page.getByLabel('Description')).toHaveValue(description);
  await expect(panel.getByText('Inactive')).toBeVisible();
});

test('manager deactivates a product, it leaves the shop, and activates it again', async ({
  page,
}) => {
  await logInAsManager(page);
  await findInManagerList(page, ROUTER);
  const row = managerRow(page, ROUTER);
  // A live run that stopped halfway may have left it inactive (npm run seed restores it).
  if (await row.getByRole('button', { name: 'Activate', exact: true }).isVisible()) {
    await row.getByRole('button', { name: 'Activate', exact: true }).click();
    await expect(row.getByText('Active', { exact: true })).toBeVisible();
  }

  await row.getByRole('button', { name: 'Deactivate', exact: true }).click();
  const confirm = page.getByRole('dialog', { name: 'Deactivate this product?' });
  await expect(confirm).toContainText('Customers will no longer see it in the shop.');
  await confirm.getByRole('button', { name: 'Deactivate', exact: true }).click();
  await expect(page.getByText('Product deactivated')).toBeVisible();
  await expect(row.getByText('Inactive', { exact: true })).toBeVisible();

  await searchStorefront(page, ROUTER);
  await expect(page.getByText('No products match your filters')).toBeVisible();

  await findInManagerList(page, ROUTER);
  await row.getByRole('button', { name: 'Activate', exact: true }).click();
  await expect(page.getByText('Product activated')).toBeVisible();
  await expect(row.getByText('Active', { exact: true })).toBeVisible();

  await searchStorefront(page, ROUTER);
  await expect(page.getByTestId('product-tile').filter({ hasText: ROUTER })).toHaveCount(1);
});

test('the manager product list has no horizontal page scroll at 375 px', async ({ page }) => {
  await logInAsManager(page);
  await page.setViewportSize({ width: 375, height: 800 });
  await expect(page.getByTestId('product-row').first()).toBeVisible();
  await expectNoHorizontalScroll(page);
});
