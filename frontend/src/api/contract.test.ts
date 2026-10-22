import { describe, expect, expectTypeOf, it } from 'vitest';
import {
  fillPath,
  pathsOf,
  type JsonBody,
  type Ok,
  type Page,
  type PathParams,
  type Query,
  type Received,
} from './contract';
import type {
  components as CatalogComponents,
  operations as CatalogOps,
  paths as CatalogPaths,
} from './generated/catalog';
import type { components as CustomerComponents } from './generated/customer';
import type {
  components as InventoryComponents,
  operations as InventoryOps,
} from './generated/inventory';

type Catalog = CatalogComponents['schemas'];
type Inventory = InventoryComponents['schemas'];

describe('operation helpers', () => {
  it('reads body, query, path parameters and success body by operationId', () => {
    expectTypeOf<JsonBody<CatalogOps['createProduct']>>().toEqualTypeOf<
      Catalog['ProductRequest']
    >();
    expectTypeOf<Query<CatalogOps['browseProducts']>['status']>().toEqualTypeOf<
      'ACTIVE' | 'NOT_ACTIVE' | undefined
    >();
    expectTypeOf<PathParams<CatalogOps['getProduct']>>().toEqualTypeOf<{ id: string }>();
    expectTypeOf<Ok<CatalogOps['getProduct']>>().toEqualTypeOf<Catalog['ProductDetailsResponse']>();
    expectTypeOf<Ok<CatalogOps['createProduct']>>().toEqualTypeOf<
      Catalog['CreateProductResponse']
    >();
  });

  it('gives void for an answer without content (204)', () => {
    expectTypeOf<Ok<InventoryOps['setPackaging']>>().toEqualTypeOf<void>();
  });
});

describe('Received', () => {
  type Profile = Received<CustomerComponents['schemas']['ProfileResponse']>;

  it('Optional field answered as null: an optional property may be null or missing', () => {
    expectTypeOf<Profile['firstName']>().toEqualTypeOf<string | null | undefined>();
  });

  it('Required fields present: required properties keep their type', () => {
    type Summary = Received<Catalog['ProductSummaryResponse']>;
    expectTypeOf<Summary['title']>().toEqualTypeOf<string>();
    expectTypeOf<Summary['basePrice']>().toEqualTypeOf<number>();
    expectTypeOf<
      Received<Catalog['PageResponseProductSummaryResponse']>['content']
    >().toEqualTypeOf<Summary[]>();
  });

  it('keeps maps such as the 400 errors as they are', () => {
    type Errors = NonNullable<Received<Catalog['ValidationProblem']>['errors']>;
    expectTypeOf<Errors>().toEqualTypeOf<{ [key: string]: string[] }>();
  });
});

describe('Page', () => {
  it('equals the generated paging envelopes', () => {
    expectTypeOf<Page<Catalog['ProductSummaryResponse']>>().toEqualTypeOf<
      Catalog['PageResponseProductSummaryResponse']
    >();
    expectTypeOf<Page<Inventory['StockBalanceResponse']>>().toEqualTypeOf<
      Inventory['PageResponseStockBalanceResponse']
    >();
  });
});

describe('pathsOf', () => {
  const pathFor = pathsOf<CatalogPaths>();

  it('fills and URL-encodes path parameters', () => {
    expect(pathFor('/catalog/products/{id}', { id: 'a b/c' })).toBe('/catalog/products/a%20b%2Fc');
    expect(pathFor('/catalog/products')).toBe('/catalog/products');
  });

  it('accepts only paths of the document', () => {
    // Type-level only: never called.
    const unknownPath = () =>
      // @ts-expect-error not a Catalog path
      pathFor('/catalog/product');
    const missingId = () =>
      // @ts-expect-error the id is required
      pathFor('/catalog/products/{id}');
    expect([unknownPath, missingId]).toHaveLength(2);
  });

  it('fails on a missing parameter at runtime too', () => {
    expect(() => fillPath('/x/{id}')).toThrow('Missing path parameter "id"');
  });
});
