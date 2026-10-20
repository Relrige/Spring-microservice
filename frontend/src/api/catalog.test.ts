import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../test/server';
import { getProduct, listProducts, toPrice } from './catalog';
import { configureHttp } from './http';

describe('catalog API', () => {
  it('accepts basePrice as a number or a string', async () => {
    server.use(
      http.get('*/api/catalog/products', () =>
        HttpResponse.json({
          content: [
            {
              productId: 'a',
              title: 'A',
              basePrice: '1299.00',
              categoryId: 'c',
              status: 'ACTIVE',
              stockStatus: 'IN_STOCK',
            },
            {
              productId: 'b',
              title: 'B',
              basePrice: 15.5,
              categoryId: 'c',
              status: 'ACTIVE',
              stockStatus: 'IN_STOCK',
            },
          ],
          page: 0,
          totalElements: 2,
        }),
      ),
    );
    const page = await listProducts({});
    expect(page.content.map((p) => p.basePrice)).toEqual([1299, 15.5]);
    expect(toPrice('7')).toBe(7);
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
