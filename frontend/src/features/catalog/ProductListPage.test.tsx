import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { DEMO_PRODUCTS } from '../../fixtures/demo-data';
import { db } from '../../mocks/db';
import { compareText } from '../../mocks/handlers';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

const ACTIVE = DEMO_PRODUCTS.filter((p) => p.active);
const byTitle = compareText;

function recordProductRequests() {
  const requests: Request[] = [];
  server.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname === '/api/catalog/products') requests.push(request.clone());
  });
  return requests;
}

async function tiles() {
  await screen.findAllByTestId('product-tile');
  return screen.getAllByTestId('product-tile');
}

function tileTitles(): string[] {
  return screen
    .getAllByTestId('product-tile')
    .map((tile) => within(tile).getByRole('heading').textContent!);
}

function categoryId(name: string) {
  return db.categories.find((c) => c.name === name)!.categoryId;
}

describe('Product list', () => {
  it('List loads: first 20 active products by title with price, stock and link', async () => {
    renderApp('/');
    expect(await tiles()).toHaveLength(20);
    const expected = ACTIVE.map((p) => p.title)
      .sort(byTitle)
      .slice(0, 20);
    expect(tileTitles()).toEqual(expected);
    expect(screen.getByText(`${ACTIVE.length} products`)).toBeInTheDocument();

    const first = screen.getAllByTestId('product-tile')[0]!;
    const product = db.products.find((p) => p.title === expected[0])!;
    expect(first).toHaveAttribute('href', `/products/${product.productId}`);
    expect(within(first).getByText(/^UAH /)).toBeInTheDocument();
    expect(within(first).getByText(/In stock|Out of stock/)).toBeInTheDocument();
  });

  it('Out-of-stock product is listed with an "Out of stock" label', async () => {
    const outOfStock = ACTIVE.find((p) => p.targetStock === 0)!;
    renderApp(`/?search=${encodeURIComponent(outOfStock.title)}`);
    const [tile] = await tiles();
    expect(within(tile!).getByText('Out of stock')).toBeInTheDocument();
  });

  it('Empty catalog: message and a control that clears the filters', async () => {
    const { router } = renderApp('/?search=no-such-thing-at-all');
    expect(await screen.findByText('No products match your filters')).toBeInTheDocument();
    const products = screen.getByRole('region', { name: 'Products' });
    await userEvent.click(within(products).getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/'));
    expect(await tiles()).toHaveLength(20);
  });

  it('Manager browses the storefront: no Authorization header, only active products', async () => {
    loginAs('CATALOG_MANAGER');
    const requests = recordProductRequests();
    renderApp('/');
    await tiles();
    expect(screen.getByText(`${ACTIVE.length} products`)).toBeInTheDocument();
    expect(requests.length).toBeGreaterThan(0);
    expect(requests.every((r) => r.headers.get('Authorization') === null)).toBe(true);
    server.events.removeAllListeners();
  });

  it('Catalog service down: unavailable message and Try again reloads the list', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/catalog/products', () => {
        calls += 1;
        return calls <= 2
          ? new HttpResponse(null, { status: 502 })
          : HttpResponse.json({ content: [], page: 0, totalElements: 0 });
      }),
    );
    renderApp('/');
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable');
    expect(screen.queryByTestId('product-tile')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('No products match your filters')).toBeInTheDocument();
  });
});

describe('Search and filters', () => {
  it('Search by keyword: requests search=laptop from the first page', async () => {
    const requests = recordProductRequests();
    const { router } = renderApp('/?page=2');
    await tiles();
    await userEvent.type(screen.getByRole('searchbox', { name: 'Search products' }), 'laptop');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/?search=laptop'));
    await waitFor(() => {
      const url = new URL(requests.at(-1)!.url);
      expect(url.searchParams.get('search')).toBe('laptop');
      expect(url.searchParams.get('page')).toBe('0');
    });
    server.events.removeAllListeners();
  });

  it('Filter by category: only that category, starting at page 1', async () => {
    const { router } = renderApp('/?page=2');
    await tiles();
    await userEvent.click(await screen.findByRole('radio', { name: 'Phones' }));
    await waitFor(() => expect(currentUrl(router)).toBe(`/?category=${categoryId('Phones')}`));
    await waitFor(() => {
      const phones = ACTIVE.filter((p) => p.category === 'Phones').map((p) => p.title);
      expect(tileTitles()).toEqual([...phones].sort(byTitle));
    });
    expect(screen.getByRole('heading', { level: 1, name: 'Phones' })).toBeInTheDocument();
  });

  it('Invalid price range: shown at the price fields and not requested', async () => {
    const requests = recordProductRequests();
    const { router } = renderApp('/');
    await tiles();
    const before = requests.length;
    await userEvent.type(screen.getByLabelText('Minimum price'), '500');
    await userEvent.type(screen.getByLabelText('Maximum price'), '100');
    await userEvent.click(screen.getByRole('button', { name: 'Apply price' }));
    expect(await screen.findByText('Minimum must not be greater than maximum')).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('Minimum price'));
    await userEvent.type(screen.getByLabelText('Minimum price'), '-1');
    await userEvent.click(screen.getByRole('button', { name: 'Apply price' }));
    expect(await screen.findByText('Enter a price of 0 or more')).toBeInTheDocument();
    expect(requests.length).toBe(before);
    expect(currentUrl(router)).toBe('/');
    server.events.removeAllListeners();
  });

  it('applies a valid price range', async () => {
    const { router } = renderApp('/');
    await tiles();
    await userEvent.type(screen.getByLabelText('Minimum price'), '1000');
    await userEvent.type(screen.getByLabelText('Maximum price'), '2000');
    await userEvent.click(screen.getByRole('button', { name: 'Apply price' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/?minPrice=1000&maxPrice=2000'));
    await waitFor(() => {
      const expected = ACTIVE.filter((p) => p.price >= 1000 && p.price <= 2000);
      expect(screen.getAllByTestId('product-tile')).toHaveLength(expected.length);
    });
  });

  it('Categories unavailable: the list works and the filter says so', async () => {
    server.use(http.get('*/api/catalog/categories', () => new HttpResponse(null, { status: 503 })));
    renderApp('/');
    expect(await tiles()).toHaveLength(20);
    expect(await screen.findByText('Categories could not be loaded.')).toBeInTheDocument();
  });
});

describe('Sorting', () => {
  it('Sort by price: sortBy=basePrice,asc and shown in that order', async () => {
    const requests = recordProductRequests();
    const { router } = renderApp('/');
    await tiles();
    expect(screen.getByLabelText('Sort by')).toHaveValue('title,asc');
    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'Price low to high');
    await waitFor(() => expect(currentUrl(router)).toBe('/?sort=basePrice%2Casc'));
    await waitFor(() => {
      const cheapest = [...ACTIVE].sort((a, b) => a.price - b.price)[0]!;
      expect(tileTitles()[0]).toBe(cheapest.title);
    });
    expect(new URL(requests.at(-1)!.url).searchParams.get('sortBy')).toBe('basePrice,asc');
    server.events.removeAllListeners();
  });
});

describe('Paging', () => {
  it('Next page: 45 products, Next loads 21–40, on the third page Next is disabled', async () => {
    // 45 matching products: make the three inactive fixtures active for this test.
    for (const product of db.products) product.status = 'ACTIVE';
    const all = db.products.map((p) => p.title).sort(byTitle);
    const { router } = renderApp('/');
    await tiles();
    expect(screen.getByText('45 products')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/?page=2'));
    await waitFor(() => expect(tileTitles()).toEqual(all.slice(20, 40)));
    expect(screen.getByRole('button', { name: 'Page 2' })).toHaveAttribute('aria-current', 'page');

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(tileTitles()).toEqual(all.slice(40)));
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });
});

describe('List state in the URL', () => {
  it('Shared link: second page of "tv"-like matches, most expensive first', async () => {
    renderApp('/?search=a&sort=basePrice,desc&page=2');
    await tiles();
    const matches = ACTIVE.filter(
      (p) => p.title.toLowerCase().includes('a') || p.description.toLowerCase().includes('a'),
    ).sort((a, b) => b.price - a.price);
    expect(tileTitles()).toEqual(matches.slice(20, 40).map((p) => p.title));
    expect(screen.getByLabelText('Sort by')).toHaveValue('basePrice,desc');
    expect(screen.getByRole('searchbox', { name: 'Search products' })).toHaveValue('a');
  });

  it('Back button: the previous category selection and its results come back', async () => {
    const { router } = renderApp(`/?category=${categoryId('Audio')}`);
    await tiles();
    await userEvent.click(await screen.findByRole('radio', { name: 'Laptops' }));
    await waitFor(() => expect(currentUrl(router)).toBe(`/?category=${categoryId('Laptops')}`));
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Laptops'),
    );
    await router.navigate(-1);
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Audio'),
    );
    expect(screen.getByRole('radio', { name: 'Audio' })).toBeChecked();
    const audio = ACTIVE.filter((p) => p.category === 'Audio').map((p) => p.title);
    await waitFor(() => expect(tileTitles()).toEqual([...audio].sort(byTitle)));
  });

  it('Invalid URL value: page=-3 and an unknown sort show the defaults', async () => {
    renderApp('/?page=-3&sort=nope');
    await tiles();
    expect(screen.getByLabelText('Sort by')).toHaveValue('title,asc');
    expect(tileTitles()).toEqual(
      ACTIVE.map((p) => p.title)
        .sort(byTitle)
        .slice(0, 20),
    );
  });
});

describe('Product image placeholder in the list', () => {
  it('shows the category placeholder on every tile', async () => {
    renderApp(`/?category=${categoryId('Phones')}`);
    await tiles();
    await waitFor(() => {
      const images = screen.getAllByRole('img', { name: 'Phones placeholder image' });
      expect(images.length).toBe(screen.getAllByTestId('product-tile').length);
    });
  });
});
