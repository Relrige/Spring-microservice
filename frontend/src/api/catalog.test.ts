import { http, HttpResponse } from 'msw';
import { describe, expect, expectTypeOf, it } from 'vitest';
import { server } from '../test/server';
import {
  activationRejection,
  deleteCategory,
  getProduct,
  listCategories,
  listProducts,
  type ActivationRejected,
  type ProductDetails,
  type ProductInput,
} from './catalog';
import type { JsonBody, Ok, Received } from './contract';
import { ApiError, PROBLEM_TYPE_PREFIX } from './errors';
import type { components, operations } from './generated/catalog';
import { configureHttp } from './http';

type Schemas = components['schemas'];

describe('catalog API types', () => {
  it('the manager aliases and bodies match the generated types', () => {
    expectTypeOf<ActivationRejected>().toEqualTypeOf<
      Received<Schemas['ActivationRejectedProblem']>
    >();
    expectTypeOf<ProductInput>().toEqualTypeOf<Schemas['ProductRequest']>();
    expectTypeOf<JsonBody<operations['updateProduct']>>().toEqualTypeOf<ProductInput>();
    expectTypeOf<JsonBody<operations['renameCategory']>>().toEqualTypeOf<
      Schemas['CategoryRequest']
    >();
    expectTypeOf<Received<Ok<operations['updateProduct']>>>().toEqualTypeOf<ProductDetails>();
    expectTypeOf<Ok<operations['deleteCategory']>>().toEqualTypeOf<void>();
  });
});

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

  it('sends the session token on getProduct with auth session, none by default', async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.get('*/api/catalog/products/:id', ({ request }) => {
        seen.push(request.headers.get('Authorization'));
        return HttpResponse.json({
          productId: 'a',
          title: 'A',
          description: 'D',
          categoryId: 'c',
          basePrice: 1,
          status: 'NOT_ACTIVE',
          stockStatus: 'OUT_OF_STOCK',
        } satisfies Schemas['ProductResponse']);
      }),
    );
    configureHttp({ getToken: () => 'manager-token' });
    await getProduct('a', { auth: 'session' });
    await getProduct('a');
    configureHttp({ getToken: () => null });
    expect(seen).toEqual(['Bearer manager-token', null]);
  });

  it('deleteCategory resolves on a 200 with an empty body', async () => {
    server.use(
      http.delete('*/api/catalog/categories/:id', () => new HttpResponse(null, { status: 200 })),
    );
    await expect(deleteCategory('c')).resolves.toBeUndefined();
  });

  it('reads activation-rejected problems by type only', () => {
    const problem = (type: string) =>
      new ApiError('unprocessable', {
        status: 422,
        problem: {
          type,
          title: 'Unprocessable Entity',
          status: 422,
          reason: 'packaging_data_missing',
        },
      });
    expect(activationRejection(problem(`${PROBLEM_TYPE_PREFIX}activation-rejected`))?.reason).toBe(
      'packaging_data_missing',
    );
    expect(
      activationRejection(problem(`${PROBLEM_TYPE_PREFIX}unprocessable-entity`)),
    ).toBeUndefined();
    expect(activationRejection(new ApiError('network'))).toBeUndefined();
  });
});
