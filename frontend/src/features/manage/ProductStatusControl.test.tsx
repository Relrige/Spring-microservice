import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

const inactive = () => db.products.find((p) => p.status === 'NOT_ACTIVE')!;
const active = () => db.products.find((p) => p.status === 'ACTIVE')!;

function recordStatusRequests() {
  const requests: Request[] = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method === 'PATCH') requests.push(request.clone());
  });
  return requests;
}

async function openEdit(productId: string) {
  renderApp(`/manage/products/${productId}`);
  await screen.findByRole('textbox', { name: 'Title' });
  return screen.getByRole('complementary', { name: 'Product status' });
}

function rejectActivation(reason: string, detail?: string) {
  server.use(
    http.patch('*/api/catalog/products/:id/status', () =>
      problem(
        422,
        detail as string,
        { title: 'Product cannot be activated', reason, ...(detail ? {} : { detail: undefined }) },
        'catalog-service',
        'activation-rejected',
      ),
    ),
  );
}

describe('Activate a product', () => {
  it('Product activated: 200, "Active" and the notice', async () => {
    loginAs('CATALOG_MANAGER');
    const product = inactive();
    const requests = recordStatusRequests();
    const panel = await openEdit(product.productId);
    await userEvent.click(within(panel).getByRole('button', { name: 'Activate' }));
    expect(await screen.findByText('Product activated')).toBeInTheDocument();
    await waitFor(() => expect(within(panel).getByText('Active')).toBeInTheDocument());
    expect(within(panel).getByRole('button', { name: 'Deactivate' })).toBeInTheDocument();
    expect(await requests[0]!.json()).toEqual({ status: 'ACTIVE' });
  });

  it('Packaging missing: the reason in words and the product stays Inactive', async () => {
    loginAs('CATALOG_MANAGER');
    rejectActivation('packaging_data_missing', 'The product is not ready for activation.');
    const panel = await openEdit(inactive().productId);
    await userEvent.click(within(panel).getByRole('button', { name: 'Activate' }));
    expect(
      await within(panel).findByText(
        'This product cannot be activated yet: its packaging size and weight are not set. An inventory worker sets them in the warehouse.',
      ),
    ).toBeInTheDocument();
    expect(within(panel).getByText('Inactive')).toBeInTheDocument();
  });

  it('No stock record yet: the reason in words', async () => {
    loginAs('CATALOG_MANAGER');
    rejectActivation('stock_item_not_found', 'The product is not ready for activation.');
    const panel = await openEdit(inactive().productId);
    await userEvent.click(within(panel).getByRole('button', { name: 'Activate' }));
    expect(
      await within(panel).findByText(
        'This product cannot be activated yet: the warehouse has no stock record for it. Try again in a moment.',
      ),
    ).toBeInTheDocument();
    expect(within(panel).getByText('Inactive')).toBeInTheDocument();
  });

  it('Unknown reason: "This product cannot be activated: warehouse_closed"', async () => {
    loginAs('CATALOG_MANAGER');
    rejectActivation('warehouse_closed');
    const panel = await openEdit(inactive().productId);
    await userEvent.click(within(panel).getByRole('button', { name: 'Activate' }));
    expect(
      await within(panel).findByText('This product cannot be activated: warehouse_closed'),
    ).toBeInTheDocument();
  });

  it('Inventory down: the "temporarily unavailable" message with the wait, one request only', async () => {
    loginAs('CATALOG_MANAGER');
    const requests = recordStatusRequests();
    server.use(
      http.patch('*/api/catalog/products/:id/status', () =>
        problem(
          503,
          'Stock readiness cannot be checked right now. Please try again later.',
          {},
          'catalog-service',
          undefined,
          { 'Retry-After': '5' },
        ),
      ),
    );
    const panel = await openEdit(inactive().productId);
    await userEvent.click(within(panel).getByRole('button', { name: 'Activate' }));
    expect(
      await within(panel).findByText(
        'This service is temporarily unavailable. Try again in 5 seconds.',
      ),
    ).toBeInTheDocument();
    expect(within(panel).getByText('Inactive')).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requests).toHaveLength(1);
  });

  it('a 409 on the edit page offers Reload product', async () => {
    loginAs('CATALOG_MANAGER');
    server.use(
      http.patch('*/api/catalog/products/:id/status', () =>
        problem(409, 'The product was changed by someone else.', {}, 'catalog-service'),
      ),
    );
    const panel = await openEdit(inactive().productId);
    await userEvent.click(within(panel).getByRole('button', { name: 'Activate' }));
    expect(
      await within(panel).findByText(
        'This product was changed by someone else at the same time. Reload it and try again.',
      ),
    ).toBeInTheDocument();
    await userEvent.click(within(panel).getByRole('button', { name: 'Reload product' }));
    await waitFor(() =>
      expect(within(panel).queryByRole('button', { name: 'Reload product' })).toBeNull(),
    );
  });
});

describe('Deactivate a product', () => {
  it('Product deactivated: confirmation, 200 and "Inactive"', async () => {
    loginAs('CATALOG_MANAGER');
    const product = active();
    const panel = await openEdit(product.productId);
    await userEvent.click(within(panel).getByRole('button', { name: 'Deactivate' }));
    const dialog = await screen.findByRole('dialog', { name: 'Deactivate this product?' });
    expect(within(dialog).getByText('Customers will no longer see it in the shop.')).toBeVisible();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }));
    expect(await screen.findByText('Product deactivated')).toBeInTheDocument();
    await waitFor(() => expect(within(panel).getByText('Inactive')).toBeInTheDocument());
    expect(db.products.find((p) => p.productId === product.productId)!.status).toBe('NOT_ACTIVE');
  });

  it('Deactivation cancelled: no request, the product stays Active', async () => {
    loginAs('CATALOG_MANAGER');
    const requests = recordStatusRequests();
    const panel = await openEdit(active().productId);
    await userEvent.click(within(panel).getByRole('button', { name: 'Deactivate' }));
    const dialog = await screen.findByRole('dialog', { name: 'Deactivate this product?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(requests).toHaveLength(0);
    expect(within(panel).getByText('Active')).toBeInTheDocument();
  });
});

describe('Status changes on the list', () => {
  const rowOf = (title: string) =>
    screen
      .getAllByTestId('product-row')
      .find((r) => within(r).queryByRole('link', { name: title }))!;

  it('activates from a row; the row shows the status read back', async () => {
    loginAs('CATALOG_MANAGER');
    const product = inactive();
    renderApp(`/manage/products?search=${encodeURIComponent(product.title)}`);
    await screen.findAllByTestId('product-row');
    await userEvent.click(within(rowOf(product.title)).getByRole('button', { name: 'Activate' }));
    await waitFor(() => expect(within(rowOf(product.title)).getByText('Active')).toBeVisible());
  });

  it('a rejection names the product above the table', async () => {
    loginAs('CATALOG_MANAGER');
    rejectActivation('packaging_data_missing', 'The product is not ready for activation.');
    const product = inactive();
    renderApp(`/manage/products?search=${encodeURIComponent(product.title)}`);
    await screen.findAllByTestId('product-row');
    await userEvent.click(within(rowOf(product.title)).getByRole('button', { name: 'Activate' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      `${product.title}: This product cannot be activated yet: its packaging size`,
    );
  });

  it('a 409 on the list reads the list again', async () => {
    loginAs('CATALOG_MANAGER');
    const product = inactive();
    let listReads = 0;
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'GET' && new URL(request.url).pathname === '/api/catalog/products') {
        listReads += 1;
      }
    });
    server.use(
      http.patch('*/api/catalog/products/:id/status', () =>
        problem(409, 'The product was changed by someone else.', {}, 'catalog-service'),
      ),
    );
    renderApp(`/manage/products?search=${encodeURIComponent(product.title)}`);
    await screen.findAllByTestId('product-row');
    const before = listReads;
    await userEvent.click(within(rowOf(product.title)).getByRole('button', { name: 'Activate' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed by someone else/);
    await waitFor(() => expect(listReads).toBeGreaterThan(before));
    expect(screen.queryByRole('button', { name: 'Reload product' })).not.toBeInTheDocument();
  });
});

describe('Manager changes reach the storefront', () => {
  it('Deactivated product leaves the storefront', async () => {
    loginAs('CATALOG_MANAGER');
    const product = active();
    const { router } = renderApp(`/?search=${encodeURIComponent(product.title)}`);
    expect(await screen.findAllByTestId('product-tile')).toHaveLength(1);
    await router.navigate(`/manage/products/${product.productId}`);
    const panel = await screen.findByRole('complementary', { name: 'Product status' });
    await userEvent.click(within(panel).getByRole('button', { name: 'Deactivate' }));
    const dialog = await screen.findByRole('dialog', { name: 'Deactivate this product?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Deactivate' }));
    await waitFor(() => expect(within(panel).getByText('Inactive')).toBeInTheDocument());
    await router.navigate(`/?search=${encodeURIComponent(product.title)}`);
    expect(await screen.findByText('No products match your filters')).toBeInTheDocument();
  });

  it('Staff list does not leak into the storefront: no token, active products only', async () => {
    loginAs('CATALOG_MANAGER');
    const storefront: Request[] = [];
    const { router } = renderApp('/manage/products?search=xiaomi');
    const rows = await screen.findAllByTestId('product-row');
    expect(rows.some((r) => within(r).queryByText('Inactive'))).toBe(true);
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname === '/api/catalog/products')
        storefront.push(request.clone());
    });
    await router.navigate('/?search=xiaomi');
    const tiles = await screen.findAllByTestId('product-tile');
    expect(storefront[0]!.headers.get('Authorization')).toBeNull();
    const activeXiaomi = db.products.filter(
      (p) => p.status === 'ACTIVE' && p.title.toLowerCase().includes('xiaomi'),
    );
    expect(tiles).toHaveLength(activeXiaomi.length);
  });
});
