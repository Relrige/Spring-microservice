import { describe, expect, expectTypeOf, it } from 'vitest';
import type { ProductInput, ProductQuery, ProductStatus } from '../../api/catalog';
import { ApiError, PROBLEM_TYPE_PREFIX } from '../../api/errors';
import {
  categoryError,
  categoryNameError,
  descriptionError,
  priceError,
  titleError,
  toProductInput,
  type ProductFormValues,
} from './field-rules';
import { activationMessage, OVERLAP_MESSAGE, statusErrorMessage } from './messages';
import {
  DEFAULT_CATEGORIES_STATE,
  DEFAULT_PRODUCTS_STATE,
  parseCategoriesState,
  parseProductsState,
  serializeCategoriesState,
  serializeProductsState,
  STATUS_OPTIONS,
  toCategoryQuery,
  toManagedProductQuery,
} from './url-state';

const CATEGORY = '00000000-0000-4000-a000-000000000001';

describe('manager product list URL state', () => {
  it('the status filter values are the generated status enum of browseProducts', () => {
    expectTypeOf<(typeof STATUS_OPTIONS)[number]['value']>().toEqualTypeOf<
      NonNullable<ProductQuery['status']>
    >();
    expectTypeOf<ProductStatus>().toEqualTypeOf<NonNullable<ProductQuery['status']>>();
  });

  it('parses a full query string and serializes it back', () => {
    const query = `search=pixel&category=${CATEGORY}&status=NOT_ACTIVE&sort=basePrice%2Cdesc&page=3`;
    const state = parseProductsState(new URLSearchParams(query));
    expect(state).toEqual({
      search: 'pixel',
      category: CATEGORY,
      status: 'NOT_ACTIVE',
      sort: 'basePrice,desc',
      page: 3,
    });
    expect(serializeProductsState(state).toString()).toBe(query);
  });

  it('falls back to the defaults for invalid values and leaves defaults out', () => {
    const state = parseProductsState(
      new URLSearchParams('status=DELETED&category=nope&sort=random&page=0'),
    );
    expect(state).toEqual(DEFAULT_PRODUCTS_STATE);
    expect(serializeProductsState(state).toString()).toBe('');
  });

  it('builds the EP-CAT-04 query with status and a zero-based page', () => {
    expect(toManagedProductQuery({ ...DEFAULT_PRODUCTS_STATE, status: 'ACTIVE', page: 2 })).toEqual(
      {
        search: undefined,
        categoryId: undefined,
        status: 'ACTIVE',
        sortBy: 'title,asc',
        page: 1,
        size: 20,
      },
    );
  });
});

describe('manager category list URL state', () => {
  it('parses, serializes and leaves defaults out', () => {
    const state = parseCategoriesState(new URLSearchParams('search=pho&page=2&status=ACTIVE'));
    expect(state).toEqual({ search: 'pho', page: 2 });
    expect(serializeCategoriesState(state).toString()).toBe('search=pho&page=2');
    expect(parseCategoriesState(new URLSearchParams('page=-1'))).toEqual(DEFAULT_CATEGORIES_STATE);
    expect(serializeCategoriesState(DEFAULT_CATEGORIES_STATE).toString()).toBe('');
  });

  it('builds the EP-CAT-10 query', () => {
    expect(toCategoryQuery({ search: 'pho', page: 2 })).toEqual({
      search: 'pho',
      page: 1,
      size: 20,
    });
    expect(toCategoryQuery(DEFAULT_CATEGORIES_STATE).search).toBeUndefined();
  });
});

describe('activation reasons', () => {
  const rejected = (reason: string, detail?: string) => ({
    type: `${PROBLEM_TYPE_PREFIX}activation-rejected`,
    title: 'Product cannot be activated',
    status: 422,
    reason,
    detail,
  });

  it('Packaging missing', () => {
    expect(activationMessage(rejected('packaging_data_missing'))).toBe(
      'This product cannot be activated yet: its packaging size and weight are not set. An inventory worker sets them in the warehouse.',
    );
  });

  it('No stock record yet', () => {
    expect(activationMessage(rejected('stock_item_not_found', 'ignored'))).toBe(
      'This product cannot be activated yet: the warehouse has no stock record for it. Try again in a moment.',
    );
  });

  it('Unknown reason: the detail when there is one, the reason otherwise', () => {
    expect(activationMessage(rejected('warehouse_closed'))).toBe(
      'This product cannot be activated: warehouse_closed',
    );
    expect(activationMessage(rejected('warehouse_closed', 'Closed for inventory.'))).toBe(
      'This product cannot be activated: Closed for inventory.',
    );
  });

  it('maps status errors: rejection, overlap conflict, and the rest through userMessage', () => {
    const rejection = new ApiError('unprocessable', {
      status: 422,
      problem: rejected('packaging_data_missing'),
    });
    expect(statusErrorMessage(rejection).conflict).toBe(false);
    expect(statusErrorMessage(rejection).message).toMatch(/packaging size and weight/);
    const conflict = new ApiError('conflict', {
      status: 409,
      problem: { type: `${PROBLEM_TYPE_PREFIX}conflict`, title: 'Conflict', status: 409 },
    });
    expect(statusErrorMessage(conflict)).toEqual({ message: OVERLAP_MESSAGE, conflict: true });
    expect(
      statusErrorMessage(new ApiError('unavailable', { status: 503, retryAfter: 5 })).message,
    ).toBe('This service is temporarily unavailable. Try again in 5 seconds.');
  });
});

describe('product and category field rules', () => {
  it('title and description: required, not blank, limited after trimming', () => {
    expect(titleError('   ')).toBe('Enter a title');
    expect(titleError(` ${'a'.repeat(200)} `)).toBeNull();
    expect(titleError('a'.repeat(201))).toBe('Use at most 200 characters');
    expect(descriptionError('')).toBe('Enter a description');
    expect(descriptionError('a'.repeat(5001))).toBe('Use at most 5000 characters');
  });

  it('price: greater than 0 with at most two decimals', () => {
    expect(priceError('')).toBe('Enter a price');
    expect(priceError(0)).toBe('The price must be greater than 0');
    expect(priceError(-1)).toBe('The price must be greater than 0');
    expect(priceError(0.01)).toBeNull();
    expect(priceError(1299.99)).toBeNull();
    expect(priceError(1.234)).toBe('Use at most two decimals');
  });

  it('category: chosen; category name: not blank, at most 100 characters', () => {
    expect(categoryError(null)).toBe('Choose a category');
    expect(categoryError('id')).toBeNull();
    expect(categoryNameError('  ')).toBe('Enter a name');
    expect(categoryNameError('a'.repeat(101))).toBe('Use at most 100 characters');
    expect(categoryNameError('Tablets')).toBeNull();
  });

  it('the form fields are the ProductInput fields, sent trimmed', () => {
    expectTypeOf<keyof ProductFormValues>().toEqualTypeOf<keyof ProductInput>();
    expect(
      toProductInput({ title: ' A ', description: ' B ', categoryId: 'c', basePrice: 5 }),
    ).toEqual({ title: 'A', description: 'B', categoryId: 'c', basePrice: 5 });
  });
});
