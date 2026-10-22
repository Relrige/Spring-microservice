import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../test/server';
import { getProduct, listCategories, listProducts } from './catalog';
import { configureHttp } from './http';

describe('catalog API', () => {
  it('reads the paging envelope with size (BL-05) and prices as numbers', async () => {
    const page = await listProducts({ size: 5 });
    expect(page).toMatchObject({ page: 0, size: 5 });
    expect(page.content).toHaveLength(5);
    expect(typeof page.content[0]!.basePrice).toBe('number');
    const categories = await listCategories();
    expect(categories.size).toBe(20);
  });

  it('sends storefront requests without the session token', async () => {
    let authorization: string | null = 'unset';
    server.use(
      http.get('*/api/catalog/products/:id', ({ request }) => {
        authorization = request.headers.get('Authorization');
        return HttpResponse.json({
          productId: 'a',
          title: 'A',
          description: 'D',
          categoryId: 'c',
          basePrice: 1,
          stockStatus: 'IN_STOCK',
        });
      }),
    );
    configureHttp({ getToken: () => 'manager-token' });
    await getProduct('a');
    configureHttp({ getToken: () => null });
    expect(authorization).toBeNull();
  });
});
