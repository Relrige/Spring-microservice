import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { DEMO_PRODUCTS } from '../../fixtures/demo-data';
import { db } from '../../mocks/db';
import { compareText, problem } from '../../mocks/handlers';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

function recordListRequests() {
  const requests: Request[] = [];
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname === '/api/catalog/products') requests.push(request.clone());
  });
  return requests;
}

async function rows() {
  await screen.findAllByTestId('product-row');
  return screen.getAllByTestId('product-row');
}

function row(title: string) {
  const match = screen
    .getAllByTestId('product-row')
    .find((r) => within(r).queryByRole('link', { name: title }));
  if (!match) throw new Error(`no row for ${title}`);
  return match;
}

const titles = () =>
  screen.getAllByTestId('product-row').map((r) => within(r).getAllByRole('link')[0]!.textContent);

describe('Staff product list', () => {
  it('Inactive products are listed: the request carries the token, ordered by title', async () => {
    const token = loginAs('CATALOG_MANAGER');
    const requests = recordListRequests();
    renderApp('/manage/products?search=xiaomi');
    await rows();
    expect(requests[0]!.headers.get('Authorization')).toBe(`Bearer ${token}`);
    const xiaomi = DEMO_PRODUCTS.filter((p) => p.title.toLowerCase().includes('xiaomi'));
    expect(xiaomi.some((p) => !p.active)).toBe(true);
    expect(titles()).toEqual(xiaomi.map((p) => p.title).sort(compareText));
    const inactive = xiaomi.find((p) => !p.active)!;
    expect(within(row(inactive.title)).getByText('Inactive')).toBeInTheDocument();
    const active = xiaomi.find((p) => p.active)!;
    expect(within(row(active.title)).getByText('Active')).toBeInTheDocument();
    expect(screen.getByText(`${xiaomi.length} products`)).toBeInTheDocument();
  });

  it('lists every product, active and inactive, 20 per page', async () => {
    loginAs('CATALOG_MANAGER');
    renderApp('/manage/products');
    expect(await rows()).toHaveLength(20);
    expect(screen.getByText(`${DEMO_PRODUCTS.length} products`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Page 3' })).toBeInTheDocument();
  });

  it('Row actions follow the status: Edit and Activate, or Edit and Deactivate', async () => {
    loginAs('CATALOG_MANAGER');
    renderApp('/manage/products?search=xiaomi');
    await rows();
    const inactive = DEMO_PRODUCTS.find((p) => !p.active && p.title.includes('Xiaomi'))!;
    const active = DEMO_PRODUCTS.find((p) => p.active && p.title.includes('Xiaomi'))!;
    const inactiveRow = within(row(inactive.title));
    expect(inactiveRow.getByRole('link', { name: 'Edit' })).toHaveAttribute(
      'href',
      `/manage/products/${db.products.find((p) => p.title === inactive.title)!.productId}`,
    );
    expect(inactiveRow.getByRole('button', { name: 'Activate' })).toBeInTheDocument();
    expect(inactiveRow.queryByRole('button', { name: 'Deactivate' })).not.toBeInTheDocument();
    const activeRow = within(row(active.title));
    expect(activeRow.getByRole('link', { name: 'Edit' })).toBeInTheDocument();
    expect(activeRow.getByRole('button', { name: 'Deactivate' })).toBeInTheDocument();
  });

  it('Unknown category in a row: no category name, the neutral placeholder, the rest shown', async () => {
    loginAs('CATALOG_MANAGER');
    const product = db.products[0]!;
    product.categoryId = '00000000-0000-4000-a000-0000000000ff';
    product.imageUrl = null;
    renderApp(`/manage/products?search=${encodeURIComponent(product.title)}`);
    await rows();
    const cells = within(row(product.title)).getAllByRole('cell');
    expect(cells[2]).toHaveTextContent(/^$/);
    expect(within(cells[0]!).getByRole('img')).toHaveAttribute('data-placeholder', 'unknown');
    expect(cells[3]).toHaveTextContent(/^UAH /);
  });

  it('Only inactive products: status=NOT_ACTIVE in the request and the URL', async () => {
    loginAs('CATALOG_MANAGER');
    const requests = recordListRequests();
    const { router } = renderApp('/manage/products');
    await rows();
    await userEvent.click(screen.getByRole('radio', { name: 'Inactive' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/manage/products?status=NOT_ACTIVE'));
    await waitFor(() => expect(rows()).resolves.toHaveLength(3));
    expect(new URL(requests.at(-1)!.url).searchParams.get('status')).toBe('NOT_ACTIVE');
    for (const r of screen.getAllByTestId('product-row')) {
      expect(within(r).getByText('Inactive')).toBeInTheDocument();
    }
  });

  it('List state restored from the URL: search, status and the second page', async () => {
    loginAs('CATALOG_MANAGER');
    for (let index = 0; index < 25; index += 1) {
      db.products.push({
        ...db.products[0]!,
        productId: `00000000-0000-4000-9000-${index.toString(16).padStart(12, '0')}`,
        title: `Pixel test ${String(index).padStart(2, '0')}`,
        status: 'ACTIVE',
      });
    }
    const requests = recordListRequests();
    renderApp('/manage/products?search=pixel&status=ACTIVE&page=2');
    await rows();
    expect(screen.getByRole('searchbox', { name: 'Search products' })).toHaveValue('pixel');
    expect(screen.getByRole('radio', { name: 'Active' })).toBeChecked();
    const params = new URL(requests[0]!.url).searchParams;
    expect(params.get('search')).toBe('pixel');
    expect(params.get('status')).toBe('ACTIVE');
    expect(params.get('page')).toBe('1');
    expect(screen.getByRole('button', { name: 'Page 2' })).toHaveAttribute('aria-current', 'page');
  });

  it('searches with Enter and filters by category, back at page 1', async () => {
    loginAs('CATALOG_MANAGER');
    const { router } = renderApp('/manage/products?page=2');
    await rows();
    await userEvent.type(screen.getByRole('searchbox', { name: 'Search products' }), 'sony{Enter}');
    await waitFor(() => expect(currentUrl(router)).toBe('/manage/products?search=sony'));
    const audio = db.categories.find((c) => c.name === 'Audio')!;
    await userEvent.selectOptions(screen.getByLabelText('Category'), 'Audio');
    await waitFor(() =>
      expect(currentUrl(router)).toBe(`/manage/products?search=sony&category=${audio.categoryId}`),
    );
  });

  it('Nothing matches: the message and a control that clears the filters', async () => {
    loginAs('CATALOG_MANAGER');
    const { router } = renderApp('/manage/products?search=no-such-product&status=ACTIVE');
    expect(await screen.findByText('No products match your filters')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/manage/products'));
    expect(await rows()).toHaveLength(20);
  });

  it('Catalog unavailable: the "temporarily unavailable" message and Try again', async () => {
    loginAs('CATALOG_MANAGER');
    let calls = 0;
    server.use(
      http.get('*/api/catalog/products', () => {
        calls += 1;
        return problem(502, 'Bad gateway', {}, 'api-gateway');
      }),
    );
    renderApp('/manage/products');
    expect(await screen.findByText(/temporarily unavailable/)).toBeInTheDocument();
    const before = calls;
    server.resetHandlers();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await rows()).toHaveLength(20);
    expect(before).toBeGreaterThan(0);
  });

  it('shows skeleton rows while loading', async () => {
    loginAs('CATALOG_MANAGER');
    server.use(http.get('*/api/catalog/products', () => new Promise<never>(() => {})));
    renderApp('/manage/products');
    expect(await screen.findByText('Loading products…')).toBeInTheDocument();
  });

  it('"New product" leads to the create page', async () => {
    loginAs('CATALOG_MANAGER');
    renderApp('/manage/products');
    expect(await screen.findByRole('link', { name: 'New product' })).toHaveAttribute(
      'href',
      '/manage/products/new',
    );
  });
});
