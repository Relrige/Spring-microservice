import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { db } from '../../mocks/db';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

const SONY = 'Sony WH-1000XM5 headphones';
const sony = () => db.products.find((p) => p.title === SONY)!;

function recordStockRequests() {
  const urls: URL[] = [];
  server.events.on('request:start', ({ request }) => {
    const url = new URL(request.url);
    if (
      url.pathname.startsWith('/api/inventory/stock') ||
      url.pathname.startsWith('/api/catalog')
    ) {
      urls.push(url);
    }
  });
  return urls;
}

async function openStock(url = '/warehouse') {
  loginAs('INVENTORY_WORKER');
  const rendered = renderApp(url);
  await screen.findAllByTestId('stock-row');
  return rendered;
}

const rowOf = (title: string) =>
  screen.getByText(title).closest<HTMLElement>('[data-testid="stock-row"]')!;

describe('Stock page', () => {
  it('Stock rows shown: title, short id, status, quantities and packaging', async () => {
    sony().packaging = null;
    await openStock('/warehouse?q=sony%20wh');
    const row = rowOf(SONY);
    expect(row).toHaveTextContent(`#${sony().productId.slice(0, 8)}`);
    expect(within(row).getByText('Active')).toBeInTheDocument();
    expect(within(row).getByTestId('available')).toHaveTextContent(String(sony().available));
    expect(within(row).getByText('Missing')).toBeInTheDocument();
    expect(screen.getByTestId('stock-count')).toHaveTextContent('1 stock items');
  });

  it('Title not copied yet: the id with a note, no status and no Catalog request', async () => {
    const productId = sony().productId;
    server.use(
      http.get('*/api/inventory/stock', () =>
        HttpResponse.json({
          content: [
            {
              productId,
              availableQuantity: 1,
              reservedQuantity: 0,
              totalQuantity: 1,
              packagingConfigured: true,
            },
          ],
          page: 0,
          size: 20,
          totalElements: 1,
        }),
      ),
    );
    const urls = recordStockRequests();
    await openStock();
    const row = rowOf('Title not available yet');
    expect(row).toHaveTextContent(`#${productId.slice(0, 8)}`);
    expect(within(row).queryByText(/^(Active|Inactive)$/)).not.toBeInTheDocument();
    expect(urls.filter((u) => u.pathname.startsWith('/api/catalog'))).toEqual([]);
  });
});

describe('Finding and ordering stock', () => {
  it('Packaging missing: packagingConfigured=false and only those rows', async () => {
    sony().packaging = null;
    const urls = recordStockRequests();
    await openStock();
    await userEvent.click(screen.getByText('Packaging missing'));
    await waitFor(() => expect(screen.getAllByTestId('stock-row')).toHaveLength(1));
    expect(rowOf(SONY)).toBeInTheDocument();
    expect(urls.at(-1)!.searchParams.get('packagingConfigured')).toBe('false');
  });

  it('Search by title: the matching rows, and the URL keeps the search', async () => {
    const { router } = await openStock('/warehouse?page=2');
    await userEvent.type(screen.getByLabelText('Search by title'), 'sony');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/warehouse?q=sony'));
    await waitFor(() =>
      screen.getAllByTestId('stock-row').forEach((row) => expect(row).toHaveTextContent(/Sony/)),
    );
  });

  it('No matching stock: the message and Clear filters', async () => {
    const { router } = await openStock();
    await userEvent.type(screen.getByLabelText('Search by title'), 'no such product');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByText('No stock items match these filters')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/warehouse'));
    expect(await screen.findAllByTestId('stock-row')).not.toHaveLength(0);
  });
});

describe('Adjust stock', () => {
  async function adjust(mode: 'Restock' | 'Write off', quantity: string) {
    await openStock('/warehouse?q=sony%20wh');
    await userEvent.click(within(rowOf(SONY)).getByRole('button', { name: 'Adjust' }));
    const dialog = await screen.findByRole('dialog', { name: 'Adjust stock' });
    await userEvent.click(within(dialog).getByText(mode));
    await userEvent.type(within(dialog).getByLabelText('Quantity'), quantity);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    return dialog;
  }

  it('Restock: delta 5 and the row shows the new available quantity', async () => {
    const before = sony().available;
    await adjust('Restock', '5');
    await waitFor(() =>
      expect(within(rowOf(SONY)).getByTestId('available')).toHaveTextContent(String(before + 5)),
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Write-off below zero: the dialog stays with the message, the row unchanged', async () => {
    const before = sony().available;
    const dialog = await adjust('Write off', String(before + 1));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Not enough available stock to write off',
    );
    expect(sony().available).toBe(before);
  });

  it('shows the unavailable message on 503', async () => {
    server.use(
      http.post('*/api/inventory/stock/:id/adjust', () => new HttpResponse(null, { status: 503 })),
    );
    const dialog = await adjust('Restock', '1');
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'This service is temporarily unavailable.',
    );
  });
});

describe('Set packaging', () => {
  async function openPackaging(packaging: ReturnType<typeof sony>['packaging'] = null) {
    sony().packaging = packaging;
    await openStock('/warehouse?q=sony%20wh');
    await userEvent.click(within(rowOf(SONY)).getByRole('button', { name: 'Set packaging' }));
    const dialog = await screen.findByRole('dialog', { name: 'Set packaging' });
    // The form appears once the stored values are read (EP-INV-08).
    await within(dialog).findByLabelText('Gross weight, g');
    return dialog;
  }

  it('Stored values shown: the four fields start from the stored packaging', async () => {
    const dialog = await openPackaging({
      grossWeightGrams: 350,
      lengthCm: 26,
      widthCm: 21,
      heightCm: 10,
    });
    expect(within(dialog).getByLabelText('Gross weight, g')).toHaveValue('350');
    expect(within(dialog).getByLabelText('Length, cm')).toHaveValue('26');
    expect(within(dialog).getByLabelText('Width, cm')).toHaveValue('21');
    expect(within(dialog).getByLabelText('Height, cm')).toHaveValue('10');
  });

  it('Packaging not set yet: the four fields are empty', async () => {
    const dialog = await openPackaging();
    for (const label of ['Gross weight, g', 'Length, cm', 'Width, cm', 'Height, cm']) {
      expect(within(dialog).getByLabelText(label)).toHaveValue('');
    }
  });

  it('a failed read shows the error with Try again, and no form', async () => {
    server.use(
      http.get('*/api/inventory/stock/:productId', () =>
        HttpResponse.json({ status: 503, title: 'Service Unavailable' }, { status: 503 }),
      ),
    );
    sony().packaging = null;
    await openStock('/warehouse?q=sony%20wh');
    await userEvent.click(within(rowOf(SONY)).getByRole('button', { name: 'Set packaging' }));
    const dialog = await screen.findByRole('dialog', { name: 'Set packaging' });
    expect(await within(dialog).findByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(
      within(dialog).queryByRole('button', { name: 'Save packaging' }),
    ).not.toBeInTheDocument();
  });

  it('Packaging saved: the four values are sent and the row is no longer Missing', async () => {
    const dialog = await openPackaging();
    for (const [label, value] of [
      ['Gross weight, g', '350'],
      ['Length, cm', '26'],
      ['Width, cm', '21'],
      ['Height, cm', '10'],
    ]) {
      await userEvent.type(within(dialog).getByLabelText(label!), value!);
    }
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save packaging' }));
    await waitFor(() => expect(within(rowOf(SONY)).queryByText('Missing')).not.toBeInTheDocument());
    expect(sony().packaging).toEqual({
      grossWeightGrams: 350,
      lengthCm: 26,
      widthCm: 21,
      heightCm: 10,
    });
  });

  it('Missing value: no request, the height field says it is required', async () => {
    const dialog = await openPackaging();
    for (const label of ['Gross weight, g', 'Length, cm', 'Width, cm']) {
      await userEvent.type(within(dialog).getByLabelText(label), '1');
    }
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save packaging' }));
    expect(await within(dialog).findByText('Required')).toBeInTheDocument();
    expect(sony().packaging).toBeNull();
  });
});
