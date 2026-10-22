import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';
import { CREATED_NOTICE } from './ProductEditPage';

const titleInput = () => screen.getByRole('textbox', { name: 'Title' });
const descriptionInput = () => screen.getByRole('textbox', { name: /^Description/ });
const priceInput = () => screen.getByRole('textbox', { name: 'Price' });

async function chooseCategory(name: string) {
  await userEvent.click(screen.getByLabelText('Category', { selector: 'input' }));
  await userEvent.click(await screen.findByRole('option', { name }));
}

function recordWrites(method: string, path: RegExp) {
  const requests: Request[] = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method === method && path.test(new URL(request.url).pathname)) {
      requests.push(request.clone());
    }
  });
  return requests;
}

async function fillNewProduct() {
  await userEvent.type(await screen.findByRole('textbox', { name: 'Title' }), '  Test speaker  ');
  await userEvent.type(descriptionInput(), 'A loud speaker. ');
  await chooseCategory('Audio');
  await userEvent.type(priceInput(), '1299.5');
}

describe('Create a product', () => {
  it('Product created: trimmed values sent, edit page with the notice, status Inactive', async () => {
    loginAs('CATALOG_MANAGER');
    const writes = recordWrites('POST', /^\/api\/catalog\/products$/);
    const { router } = renderApp('/manage/products/new');
    await fillNewProduct();
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }));

    await waitFor(() => expect(db.products.some((p) => p.title === 'Test speaker')).toBe(true));
    const product = db.products.find((p) => p.title === 'Test speaker')!;
    expect(await writes[0]!.json()).toEqual({
      title: 'Test speaker',
      description: 'A loud speaker.',
      categoryId: db.categories.find((c) => c.name === 'Audio')!.categoryId,
      basePrice: 1299.5,
    });
    await waitFor(() => expect(currentUrl(router)).toBe(`/manage/products/${product.productId}`));
    expect(await screen.findByText(CREATED_NOTICE)).toBeInTheDocument();
    const panel = await screen.findByRole('complementary', { name: 'Product status' });
    expect(within(panel).getByText('Inactive')).toBeInTheDocument();
    expect(titleInput()).toHaveValue('Test speaker');
    // The notice comes from the router state, which is cleared: a reload does not repeat it.
    expect(router.state.location.state).toBeNull();
  });

  it('Category removed meanwhile: 422 at the category field, categories read again, values kept', async () => {
    loginAs('CATALOG_MANAGER');
    let categoryReads = 0;
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname === '/api/catalog/categories') categoryReads += 1;
    });
    renderApp('/manage/products/new');
    await fillNewProduct();
    db.categories = db.categories.filter((c) => c.name !== 'Audio');
    const before = categoryReads;
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }));
    expect(
      await screen.findByText('This category no longer exists. Choose another one.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(categoryReads).toBeGreaterThan(before));
    expect(titleInput()).toHaveValue('  Test speaker  ');
    expect(descriptionInput()).toHaveValue('A loud speaker. ');
  });

  it('Blank description: required at the field, no request sent', async () => {
    loginAs('CATALOG_MANAGER');
    const writes = recordWrites('POST', /^\/api\/catalog\/products$/);
    renderApp('/manage/products/new');
    await fillNewProduct();
    await userEvent.clear(descriptionInput());
    await userEvent.type(descriptionInput(), '   ');
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }));
    expect(await screen.findByText('Enter a description')).toBeInTheDocument();
    expect(writes).toHaveLength(0);
  });

  it('Price of zero: must be greater than 0, no request sent', async () => {
    loginAs('CATALOG_MANAGER');
    const writes = recordWrites('POST', /^\/api\/catalog\/products$/);
    renderApp('/manage/products/new');
    await fillNewProduct();
    await userEvent.clear(priceInput());
    await userEvent.type(priceInput(), '0');
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }));
    expect(await screen.findByText('The price must be greater than 0')).toBeInTheDocument();
    expect(writes).toHaveLength(0);
  });

  it('checks the other field rules before sending', async () => {
    loginAs('CATALOG_MANAGER');
    renderApp('/manage/products/new');
    await userEvent.click(await screen.findByRole('button', { name: 'Create product' }));
    expect(await screen.findByText('Enter a title')).toBeInTheDocument();
    expect(screen.getByText('Choose a category')).toBeInTheDocument();
    expect(screen.getByText('Enter a price')).toBeInTheDocument();
  });

  it('a 400 with errors.title is shown at the title field', async () => {
    loginAs('CATALOG_MANAGER');
    server.use(
      http.post('*/api/catalog/products', () =>
        problem(
          400,
          'One or more fields are invalid.',
          { errors: { title: ['size must be between 0 and 200'] } },
          'catalog-service',
        ),
      ),
    );
    renderApp('/manage/products/new');
    await fillNewProduct();
    await userEvent.click(screen.getByRole('button', { name: 'Create product' }));
    expect(await screen.findByText('size must be between 0 and 200')).toBeInTheDocument();
    expect(titleInput()).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('Edit a product', () => {
  const inactive = () => db.products.find((p) => p.status === 'NOT_ACTIVE')!;

  it('Inactive product opens for editing: full description, category, price, status Inactive', async () => {
    const token = loginAs('CATALOG_MANAGER');
    const product = inactive();
    const reads: Request[] = [];
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname === `/api/catalog/products/${product.productId}`) {
        reads.push(request.clone());
      }
    });
    renderApp(`/manage/products/${product.productId}`);
    expect(await screen.findByRole('textbox', { name: 'Title' })).toHaveValue(product.title);
    expect(reads[0]!.headers.get('Authorization')).toBe(`Bearer ${token}`);
    expect(descriptionInput()).toHaveValue(product.description);
    const category = db.categories.find((c) => c.categoryId === product.categoryId)!;
    await waitFor(() =>
      expect(screen.getByLabelText('Category', { selector: 'input' })).toHaveValue(category.name),
    );
    expect(priceInput()).toHaveValue(product.basePrice.toLocaleString('en-US'));
    const panel = screen.getByRole('complementary', { name: 'Product status' });
    expect(within(panel).getByText('Inactive')).toBeInTheDocument();
    expect(within(panel).getByText(`#${product.productId.slice(0, 8)}`)).toBeInTheDocument();
  });

  it('Unknown product: "This product does not exist" with a link to the list', async () => {
    loginAs('CATALOG_MANAGER');
    renderApp('/manage/products/00000000-0000-4000-8000-ffffffffffff');
    expect(await screen.findByText('This product does not exist')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to the product list' })).toHaveAttribute(
      'href',
      '/manage/products',
    );
  });

  it('New price saved: "Changes saved" and the price from the answer', async () => {
    loginAs('CATALOG_MANAGER');
    const product = inactive();
    const writes = recordWrites('PUT', /^\/api\/catalog\/products\//);
    renderApp(`/manage/products/${product.productId}`);
    await screen.findByRole('textbox', { name: 'Title' });
    await userEvent.clear(priceInput());
    await userEvent.type(priceInput(), '777.7');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect((await writes[0]!.json()).basePrice).toBe(777.7);
    expect(db.products.find((p) => p.productId === product.productId)!.basePrice).toBe(777.7);
    expect(priceInput()).toHaveValue('777.7');
  });

  it('Two saves overlap: the message and Reload product, values kept, nothing resent', async () => {
    loginAs('CATALOG_MANAGER');
    const product = inactive();
    const writes = recordWrites('PUT', /^\/api\/catalog\/products\//);
    server.use(
      http.put('*/api/catalog/products/:id', () =>
        problem(
          409,
          'The product was changed by someone else. Reload it and try again.',
          {},
          'catalog-service',
        ),
      ),
    );
    renderApp(`/manage/products/${product.productId}`);
    await screen.findByRole('textbox', { name: 'Title' });
    await userEvent.clear(titleInput());
    await userEvent.type(titleInput(), 'My edited title');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(
      await screen.findByText(
        'This product was changed by someone else at the same time. Reload it and try again.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reload product' })).toBeInTheDocument();
    expect(titleInput()).toHaveValue('My edited title');
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(writes).toHaveLength(1);
  });

  it('Reload after a conflict: the product is read again and the form shows the server values', async () => {
    loginAs('CATALOG_MANAGER');
    const product = inactive();
    server.use(
      http.put('*/api/catalog/products/:id', () =>
        problem(409, 'The product was changed by someone else.', {}, 'catalog-service'),
      ),
    );
    renderApp(`/manage/products/${product.productId}`);
    await screen.findByRole('textbox', { name: 'Title' });
    await userEvent.clear(titleInput());
    await userEvent.type(titleInput(), 'My edited title');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByRole('button', { name: 'Reload product' });
    product.title = 'Changed by someone else';
    await userEvent.click(screen.getByRole('button', { name: 'Reload product' }));
    await waitFor(() => expect(titleInput()).toHaveValue('Changed by someone else'));
    expect(screen.queryByRole('button', { name: 'Reload product' })).not.toBeInTheDocument();
  });

  it('a background refetch updates the status panel but leaves typed values in place', async () => {
    loginAs('CATALOG_MANAGER');
    const product = inactive();
    const { queryClient } = renderApp(`/manage/products/${product.productId}`);
    await screen.findByRole('textbox', { name: 'Title' });
    await userEvent.clear(titleInput());
    await userEvent.type(titleInput(), 'Typed but not saved');
    product.title = 'Changed on the server';
    product.status = 'ACTIVE';
    await act(() => queryClient.invalidateQueries({ queryKey: ['manage'] }));
    const panel = screen.getByRole('complementary', { name: 'Product status' });
    await waitFor(() => expect(within(panel).getByText('Active')).toBeInTheDocument());
    expect(titleInput()).toHaveValue('Typed but not saved');
  });
});
