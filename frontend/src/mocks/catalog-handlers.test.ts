import { beforeEach, describe, expect, it } from 'vitest';
import type { Role } from '../api/auth';
import {
  activationRejection,
  createCategory,
  createProduct,
  deleteCategory,
  getProduct,
  renameCategory,
  setProductStatus,
  updateProduct,
  type ProductInput,
} from '../api/catalog';
import { ApiError, PROBLEM_TYPE_PREFIX } from '../api/errors';
import { db, resetDb } from './db';
import { createMockToken } from './tokens';

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('expected the request to fail');
}

const as = (role: Role) => ({ token: createMockToken(db.users.find((u) => u.role === role)!) });
const type = (kind: string) => `${PROBLEM_TYPE_PREFIX}${kind}`;
const UNKNOWN = '00000000-0000-4000-8000-ffffffffffff';

beforeEach(() => resetDb());

function validInput(): ProductInput {
  return {
    title: 'Mock speaker',
    description: 'A speaker made in the mock.',
    categoryId: db.categories[0]!.categoryId,
    basePrice: 1299.5,
  };
}

describe('mock catalog: manager routes need CATALOG_MANAGER (gateway)', () => {
  const product = () => db.products[0]!.productId;
  const category = () => db.categories[0]!.categoryId;
  const routes: [string, (auth: { token: string }) => Promise<unknown>][] = [
    ['POST /catalog/products', (auth) => createProduct(validInput(), auth)],
    ['PUT /catalog/products/{id}', (auth) => updateProduct(product(), validInput(), auth)],
    ['PATCH /catalog/products/{id}/status', (auth) => setProductStatus(product(), 'ACTIVE', auth)],
    ['POST /catalog/categories', (auth) => createCategory('New', auth)],
    ['PUT /catalog/categories/{id}', (auth) => renameCategory(category(), 'New', auth)],
    ['DELETE /catalog/categories/{id}', (auth) => deleteCategory(category(), auth)],
  ];

  it.each(routes)('%s answers 401 for a bad token and 403 for other roles', async (_, call) => {
    const unauthorized = await failure(call({ token: 'garbage' }));
    expect(unauthorized.status).toBe(401);
    expect(unauthorized.problem?.type).toBe(type('unauthorized'));
    for (const role of ['CUSTOMER', 'INVENTORY_WORKER', 'ADMIN'] as const) {
      const forbidden = await failure(call(as(role)));
      expect(forbidden.status).toBe(403);
      expect(forbidden.problem?.type).toBe(type('forbidden'));
    }
  });
});

describe('mock catalog: EP-CAT-05 staff visibility', () => {
  const inactive = () => db.products.find((p) => p.status === 'NOT_ACTIVE')!;

  it.each<[string, Role | null, number]>([
    ['anonymous', null, 404],
    ['CUSTOMER', 'CUSTOMER', 404],
    ['ADMIN', 'ADMIN', 404],
    ['CATALOG_MANAGER', 'CATALOG_MANAGER', 200],
    ['INVENTORY_WORKER', 'INVENTORY_WORKER', 200],
  ])('an inactive product for %s answers %i', async (_, role, status) => {
    const call = getProduct(inactive().productId, role ? { auth: as(role) } : {});
    if (status === 200) {
      expect(await call).toMatchObject({
        title: inactive().title,
        description: inactive().description,
        status: 'NOT_ACTIVE',
      });
    } else {
      const error = await failure(call);
      expect(error.status).toBe(404);
      expect(error.problem?.type).toBe(type('not-found'));
    }
  });

  it('answers 401 for a bad token', async () => {
    const error = await failure(getProduct(inactive().productId, { auth: { token: 'garbage' } }));
    expect(error.status).toBe(401);
  });
});

describe('mock catalog: products', () => {
  const manager = () => as('CATALOG_MANAGER');

  it('creates an inactive, out-of-stock product without packaging', async () => {
    const { productId } = await createProduct(validInput(), manager());
    expect(await getProduct(productId, { auth: manager() })).toMatchObject({
      ...validInput(),
      status: 'NOT_ACTIVE',
      stockStatus: 'OUT_OF_STOCK',
    });
    expect(db.products.find((p) => p.productId === productId)!.packaging).toBeNull();
  });

  it('answers 400 validation with errors per field', async () => {
    const error = await failure(
      createProduct(
        { title: ' ', description: 'x'.repeat(5001), categoryId: 'nope', basePrice: 0 },
        manager(),
      ),
    );
    expect(error.status).toBe(400);
    expect(error.problem?.type).toBe(type('validation'));
    expect(error.problem?.errors).toEqual({
      title: ['must not be blank'],
      description: ['size must be between 0 and 5000'],
      categoryId: ['Invalid value'],
      basePrice: ['must be greater than 0'],
    });
    const decimals = await failure(createProduct({ ...validInput(), basePrice: 1.234 }, manager()));
    expect(Object.keys(decimals.problem?.errors ?? {})).toEqual(['basePrice']);
  });

  it('answers 422 unprocessable-entity for an unknown category on create and update', async () => {
    const create = await failure(
      createProduct({ ...validInput(), categoryId: UNKNOWN }, manager()),
    );
    expect(create.status).toBe(422);
    expect(create.problem?.type).toBe(type('unprocessable-entity'));
    const id = db.products[0]!.productId;
    const update = await failure(
      updateProduct(id, { ...validInput(), categoryId: UNKNOWN }, manager()),
    );
    expect(update.problem?.type).toBe(type('unprocessable-entity'));
  });

  it('updates a product and answers the full view; 404 and 400 on update', async () => {
    const product = db.products.find((p) => p.status === 'NOT_ACTIVE')!;
    const updated = await updateProduct(product.productId, validInput(), manager());
    expect(updated).toMatchObject({ ...validInput(), status: 'NOT_ACTIVE' });
    expect((await failure(updateProduct(UNKNOWN, validInput(), manager()))).status).toBe(404);
    const invalid = await failure(
      updateProduct(product.productId, { ...validInput(), title: '' }, manager()),
    );
    expect(invalid.problem?.errors).toEqual({ title: ['must not be blank'] });
  });

  it('stores, returns and clears imageUrl; 400 at imageUrl outside the rule (Catalog task 30)', async () => {
    const image = '/images/products/speaker.svg';
    const { productId } = await createProduct({ ...validInput(), imageUrl: image }, manager());
    expect((await getProduct(productId, { auth: manager() })).imageUrl).toBe(image);
    // PUT replaces every field: without imageUrl the image is removed.
    expect((await updateProduct(productId, validInput(), manager())).imageUrl ?? null).toBeNull();
    for (const bad of ['http://cdn.example.com/a.png', '/images/../a.svg', 'a.png']) {
      const error = await failure(createProduct({ ...validInput(), imageUrl: bad }, manager()));
      expect(error.problem?.errors).toEqual({
        imageUrl: ['must be an https URL or a path under /images/'],
      });
    }
  });

  it('rejects activating a product created in the mock: activation-rejected, packaging_data_missing', async () => {
    const { productId } = await createProduct(validInput(), manager());
    const error = await failure(setProductStatus(productId, 'ACTIVE', manager()));
    expect(error.status).toBe(422);
    expect(activationRejection(error)?.reason).toBe('packaging_data_missing');
    expect((await getProduct(productId, { auth: manager() })).status).toBe('NOT_ACTIVE');
  });

  it('activates and deactivates a fixture product; 400 unknown status; 404 unknown product', async () => {
    const inactive = db.products.find((p) => p.status === 'NOT_ACTIVE')!;
    await expect(
      setProductStatus(inactive.productId, 'ACTIVE', manager()),
    ).resolves.toBeUndefined();
    expect((await getProduct(inactive.productId)).status).toBe('ACTIVE');
    await setProductStatus(inactive.productId, 'NOT_ACTIVE', manager());
    expect((await failure(getProduct(inactive.productId))).status).toBe(404);

    const unknownStatus = await failure(
      setProductStatus(inactive.productId, 'GONE' as 'ACTIVE', manager()),
    );
    expect(unknownStatus.status).toBe(400);
    expect(unknownStatus.problem?.errors).toEqual({ status: ['Invalid value'] });
    const missing = await failure(setProductStatus(UNKNOWN, 'NOT_ACTIVE', manager()));
    expect(missing.status).toBe(404);
    expect(missing.problem?.type).toBe(type('not-found'));
  });
});

describe('mock catalog: categories', () => {
  const manager = () => as('CATALOG_MANAGER');

  it('creates a category; 409 conflict for a name that differs only in case', async () => {
    const created = await createCategory('Tablets', manager());
    expect(db.categories).toContainEqual(created);
    const error = await failure(createCategory('tABLETS', manager()));
    expect(error.status).toBe(409);
    expect(error.problem?.type).toBe(type('conflict'));
    expect(error.problem?.detail).toBe('Category name already in use');
  });

  it('answers 400 for a blank or too long name', async () => {
    expect((await failure(createCategory('  ', manager()))).problem?.errors).toEqual({
      name: ['must not be blank'],
    });
    expect((await failure(createCategory('a'.repeat(101), manager()))).problem?.errors).toEqual({
      name: ['size must be between 0 and 100'],
    });
  });

  it('renames a category; its own name in another case is allowed; 409, 404 and 400', async () => {
    const audio = db.categories.find((c) => c.name === 'Audio')!;
    expect(await renameCategory(audio.categoryId, 'AUDIO', manager())).toEqual({
      categoryId: audio.categoryId,
      name: 'AUDIO',
    });
    const taken = await failure(renameCategory(audio.categoryId, 'phones', manager()));
    expect(taken.status).toBe(409);
    expect(taken.problem?.type).toBe(type('conflict'));
    const missing = await failure(renameCategory(UNKNOWN, 'Anything', manager()));
    expect(missing.status).toBe(404);
    expect(missing.problem?.type).toBe(type('not-found'));
    expect((await failure(renameCategory(audio.categoryId, '', manager()))).status).toBe(400);
  });

  it('deletes an empty category; 409 while products belong to it; 404 when gone', async () => {
    const audio = db.categories.find((c) => c.name === 'Audio')!;
    const used = await failure(deleteCategory(audio.categoryId, manager()));
    expect(used.status).toBe(409);
    expect(used.problem?.type).toBe(type('conflict'));
    const empty = await createCategory('Empty', manager());
    await expect(deleteCategory(empty.categoryId, manager())).resolves.toBeUndefined();
    expect(db.categories.some((c) => c.categoryId === empty.categoryId)).toBe(false);
    const gone = await failure(deleteCategory(empty.categoryId, manager()));
    expect(gone.status).toBe(404);
    expect(gone.problem?.type).toBe(type('not-found'));
  });
});
