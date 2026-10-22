import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { DEMO_CATEGORIES } from '../../fixtures/demo-data';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

const names = () =>
  screen.getAllByTestId('category-row').map((r) => within(r).getAllByRole('cell')[0]!.textContent);

const rowOf = (name: string) =>
  screen.getAllByTestId('category-row').find((r) => within(r).queryByText(name))!;

async function openPage(url = '/manage/categories') {
  loginAs('CATALOG_MANAGER');
  const result = renderApp(url);
  await screen.findAllByTestId('category-row');
  return result;
}

function recordCategoryRequests(method: string) {
  const requests: Request[] = [];
  server.events.on('request:start', ({ request }) => {
    if (
      request.method === method &&
      new URL(request.url).pathname.startsWith('/api/catalog/categories')
    ) {
      requests.push(request.clone());
    }
  });
  return requests;
}

describe('Category list', () => {
  it('Categories listed: by name, each with Rename and Delete', async () => {
    await openPage();
    expect(names()).toEqual([...DEMO_CATEGORIES].sort());
    const row = within(rowOf('Audio'));
    expect(row.getByRole('button', { name: 'Rename' })).toBeInTheDocument();
    expect(row.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    const audio = db.categories.find((c) => c.name === 'Audio')!;
    expect(row.getByText(`#${audio.categoryId.slice(0, 8)}`)).toBeInTheDocument();
  });

  it('Search by name: search=pho in the request and the URL, only matches listed', async () => {
    const requests = recordCategoryRequests('GET');
    const { router } = await openPage();
    await userEvent.type(
      screen.getByRole('searchbox', { name: 'Search categories' }),
      'pho{Enter}',
    );
    await waitFor(() => expect(currentUrl(router)).toBe('/manage/categories?search=pho'));
    await waitFor(() => expect(names()).toEqual(['Phones']));
    expect(requests.some((r) => new URL(r.url).searchParams.get('search') === 'pho')).toBe(true);
  });

  it('No categories match: the message and a control that clears the search', async () => {
    loginAs('CATALOG_MANAGER');
    const { router } = renderApp('/manage/categories?search=zzz');
    expect(await screen.findByText('No categories match your search')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear search' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/manage/categories'));
    expect(await screen.findAllByTestId('category-row')).toHaveLength(DEMO_CATEGORIES.length);
  });

  it('a failed load shows the message and Try again', async () => {
    loginAs('CATALOG_MANAGER');
    server.use(http.get('*/api/catalog/categories', () => problem(503, 'Down', {}, 'api-gateway')));
    renderApp('/manage/categories');
    expect(await screen.findByText(/temporarily unavailable/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

describe('Create, rename and delete', () => {
  it('Blank name: required at the field, no request sent', async () => {
    const posts = recordCategoryRequests('POST');
    await openPage();
    await userEvent.click(screen.getByRole('button', { name: 'New category' }));
    const dialog = await screen.findByRole('dialog', { name: 'New category' });
    await userEvent.type(within(dialog).getByLabelText('Name'), '   ');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create category' }));
    expect(await within(dialog).findByText('Enter a name')).toBeInTheDocument();
    expect(posts).toHaveLength(0);
  });

  it('Category created: the notice and the new category in the list', async () => {
    await openPage();
    await userEvent.click(screen.getByRole('button', { name: 'New category' }));
    const dialog = await screen.findByRole('dialog', { name: 'New category' });
    await userEvent.type(within(dialog).getByLabelText('Name'), ' Tablets ');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create category' }));
    expect(await screen.findByText('Category created')).toBeInTheDocument();
    await waitFor(() => expect(names()).toContain('Tablets'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Name in use: the name field explains, the form stays open with the name', async () => {
    await openPage();
    await userEvent.click(screen.getByRole('button', { name: 'New category' }));
    const dialog = await screen.findByRole('dialog', { name: 'New category' });
    await userEvent.type(within(dialog).getByLabelText('Name'), 'audio');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create category' }));
    expect(
      await within(dialog).findByText('A category with this name already exists.'),
    ).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Name')).toHaveValue('audio');
  });

  it('a 400 from the server is shown at the name field', async () => {
    server.use(
      http.post('*/api/catalog/categories', () =>
        problem(
          400,
          'One or more fields are invalid.',
          { errors: { name: ['size must be between 0 and 100'] } },
          'catalog-service',
        ),
      ),
    );
    await openPage();
    await userEvent.click(screen.getByRole('button', { name: 'New category' }));
    const dialog = await screen.findByRole('dialog', { name: 'New category' });
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Long');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create category' }));
    expect(await within(dialog).findByText('size must be between 0 and 100')).toBeInTheDocument();
  });

  it('Category renamed: pre-filled, the notice, the new name listed', async () => {
    await openPage();
    await userEvent.click(within(rowOf('Audio')).getByRole('button', { name: 'Rename' }));
    const dialog = await screen.findByRole('dialog', { name: 'Rename category' });
    const input = within(dialog).getByLabelText('Name');
    expect(input).toHaveValue('Audio');
    await userEvent.clear(input);
    await userEvent.type(input, 'Audio and sound');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }));
    expect(await screen.findByText('Category renamed')).toBeInTheDocument();
    await waitFor(() => expect(names()).toContain('Audio and sound'));
  });

  it('Category deleted meanwhile: the rename closes, the message shows, the list is read again', async () => {
    const gets = recordCategoryRequests('GET');
    await openPage();
    const audio = db.categories.find((c) => c.name === 'Audio')!;
    await userEvent.click(within(rowOf('Audio')).getByRole('button', { name: 'Rename' }));
    const dialog = await screen.findByRole('dialog', { name: 'Rename category' });
    db.categories = db.categories.filter((c) => c.categoryId !== audio.categoryId);
    const before = gets.length;
    await userEvent.type(within(dialog).getByLabelText('Name'), ' 2');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }));
    expect(await screen.findByText('This category no longer exists.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(gets.length).toBeGreaterThan(before));
    await waitFor(() => expect(names()).not.toContain('Audio'));
  });

  it('Empty category deleted: confirmation naming it, the notice, gone from the list', async () => {
    db.categories.push({ categoryId: '00000000-0000-4000-a000-0000000000aa', name: 'Empty' });
    await openPage();
    await userEvent.click(within(rowOf('Empty')).getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete category “Empty”?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Category deleted')).toBeInTheDocument();
    await waitFor(() => expect(names()).not.toContain('Empty'));
  });

  it('Category with products: the message about remaining products, still listed', async () => {
    await openPage();
    await userEvent.click(within(rowOf('Audio')).getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete category “Audio”?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This category still has products. Move them to another category first.',
    );
    expect(names()).toContain('Audio');
  });

  it('a 404 on delete counts as already deleted', async () => {
    db.categories.push({ categoryId: '00000000-0000-4000-a000-0000000000bb', name: 'Gone' });
    await openPage();
    await userEvent.click(within(rowOf('Gone')).getByRole('button', { name: 'Delete' }));
    db.categories = db.categories.filter((c) => c.name !== 'Gone');
    const dialog = await screen.findByRole('dialog', { name: 'Delete category “Gone”?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByText('Category deleted')).toBeInTheDocument();
    await waitFor(() => expect(names()).not.toContain('Gone'));
  });

  it('Deletion cancelled: no request sent', async () => {
    const deletes = recordCategoryRequests('DELETE');
    await openPage();
    await userEvent.click(within(rowOf('Audio')).getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete category “Audio”?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(deletes).toHaveLength(0);
  });
});

describe('Category changes reach the product pages', () => {
  it('Renamed category in the product list', async () => {
    const { router } = await openPage();
    await userEvent.click(within(rowOf('Audio')).getByRole('button', { name: 'Rename' }));
    const dialog = await screen.findByRole('dialog', { name: 'Rename category' });
    await userEvent.type(within(dialog).getByLabelText('Name'), ' gear');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Rename' }));
    await screen.findByText('Category renamed');
    const audioProduct = db.products.find(
      (p) => p.categoryId === db.categories.find((c) => c.name === 'Audio gear')!.categoryId,
    )!;
    await router.navigate(`/manage/products?search=${encodeURIComponent(audioProduct.title)}`);
    const [row] = await screen.findAllByTestId('product-row');
    expect(within(row!).getByText('Audio gear')).toBeInTheDocument();
  });

  it('New category offered in the product form', async () => {
    const { router } = await openPage();
    await userEvent.click(screen.getByRole('button', { name: 'New category' }));
    const dialog = await screen.findByRole('dialog', { name: 'New category' });
    await userEvent.type(within(dialog).getByLabelText('Name'), 'Tablets');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create category' }));
    await screen.findByText('Category created');
    await router.navigate('/manage/products/new');
    await userEvent.click(await screen.findByLabelText('Category', { selector: 'input' }));
    expect(await screen.findByRole('option', { name: 'Tablets' })).toBeInTheDocument();
  });
});
