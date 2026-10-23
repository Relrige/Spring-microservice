// Catalog Service calls: spec ../docs/design/services-specs/catalog-service-spec.md,
// types generated from ../catalog-service/openapi/public.json (AGENTS.md §3).
import { pathsOf, type JsonBody, type Ok, type Page, type Query, type Received } from './contract';
import { problemOf } from './errors';
import type { components, operations, paths } from './generated/catalog';
import { request, type RequestOptions } from './http';

type Auth = RequestOptions['auth'];
type Schemas = components['schemas'];
const pathFor = pathsOf<paths>();

export type ProductStatus = Schemas['ProductSummaryResponse']['status'];
/** Read model updated from Inventory events. */
export type StockStatus = Schemas['ProductSummaryResponse']['stockStatus'];

export type Category = Received<Schemas['CategoryResponse']>;
export type ProductSummary = Received<Schemas['ProductSummaryResponse']>;
/** EP-CAT-05 answer: the full product (storefront calls see only active products). */
export type ProductDetails = Received<Schemas['ProductResponse']>;
/** EP-CAT-03 `422` problem: Inventory's readiness `reason`, e.g. `packaging_data_missing`. */
export type ActivationRejected = Received<Schemas['ActivationRejectedProblem']>;
export type { Page };

/** The four orders the storefront offers; the document types `sortBy` as a free string. */
export type SortBy = 'title,asc' | 'title,desc' | 'basePrice,asc' | 'basePrice,desc';

/** EP-CAT-04 query (`page` zero-based, `size` default 20, max 100; `status` manager only). */
export type ProductQuery = Omit<Query<operations['browseProducts']>, 'sortBy'> & {
  sortBy?: SortBy;
};

/** EP-CAT-01 and EP-CAT-02 body. */
export type ProductInput = JsonBody<operations['createProduct']>;

/**
 * EP-CAT-04 (`browseProducts`): browse products. The storefront sends no token
 * (`auth: 'none'`, spec catalog-browsing) so a logged-in manager still sees the
 * customer view.
 */
export function listProducts(
  query: ProductQuery,
  options: { auth?: Auth; signal?: AbortSignal } = {},
): Promise<Page<ProductSummary>> {
  return request<Page<ProductSummary>>(pathFor('/catalog/products'), {
    // `ids` is sent comma-separated, as the document describes it.
    query: { ...query, ids: query.ids?.join(',') },
    auth: options.auth ?? 'none',
    signal: options.signal,
  });
}

/**
 * EP-CAT-05 (`getProduct`): product details. Without a token (the default,
 * storefront) inactive products answer `404`; with a staff token (`auth:
 * 'session'`, manager pages) they load too (BL-01).
 */
export function getProduct(
  productId: string,
  options: { auth?: Auth; signal?: AbortSignal } = {},
): Promise<ProductDetails> {
  return request<ProductDetails>(pathFor('/catalog/products/{id}', { id: productId }), {
    auth: options.auth ?? 'none',
    signal: options.signal,
  });
}

/** EP-CAT-10 (`listCategories`): categories ordered by name. */
export function listCategories(
  query: Query<operations['listCategories']> = {},
  options: { auth?: Auth; signal?: AbortSignal } = {},
): Promise<Page<Category>> {
  return request<Page<Category>>(pathFor('/catalog/categories'), {
    query: { ...query },
    auth: options.auth ?? 'none',
    signal: options.signal,
  });
}

/** EP-CAT-06 (`createCategory`): create a category (manager). `409` = name in use. */
export function createCategory(name: string, auth: Auth = 'session'): Promise<Category> {
  const body: JsonBody<operations['createCategory']> = { name };
  return request<Category>(pathFor('/catalog/categories'), { method: 'POST', body, auth });
}

/** EP-CAT-07 (`renameCategory`): rename a category (manager). `409` = name in use, `404` = gone. */
export function renameCategory(
  categoryId: string,
  name: string,
  auth: Auth = 'session',
): Promise<Category> {
  const body: JsonBody<operations['renameCategory']> = { name };
  return request<Category>(pathFor('/catalog/categories/{id}', { id: categoryId }), {
    method: 'PUT',
    body,
    auth,
  });
}

/**
 * EP-CAT-08 (`deleteCategory`): delete a category (manager); `200` with no body.
 * `409` while products still belong to it.
 */
export function deleteCategory(
  categoryId: string,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['deleteCategory']>>> {
  return request(pathFor('/catalog/categories/{id}', { id: categoryId }), {
    method: 'DELETE',
    auth,
  });
}

/** EP-CAT-01 (`createProduct`): create a product (manager); it starts `NOT_ACTIVE`. */
export function createProduct(
  body: ProductInput,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['createProduct']>>> {
  return request(pathFor('/catalog/products'), { method: 'POST', body, auth });
}

/**
 * EP-CAT-02 (`updateProduct`): replace the four product fields (manager).
 * `409` = an overlapping save, `422` = the category does not exist.
 */
export function updateProduct(
  productId: string,
  body: ProductInput,
  auth: Auth = 'session',
): Promise<ProductDetails> {
  return request<ProductDetails>(pathFor('/catalog/products/{id}', { id: productId }), {
    method: 'PUT',
    body,
    auth,
  });
}

/**
 * EP-CAT-03 (`changeStatus`): activate or deactivate (manager). `422`
 * `ActivationRejectedProblem` when not ready, `503` when Inventory is down.
 */
export function setProductStatus(
  productId: string,
  status: ProductStatus,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['changeStatus']>>> {
  const body: JsonBody<operations['changeStatus']> = { status };
  return request(pathFor('/catalog/products/{id}/status', { id: productId }), {
    method: 'PATCH',
    body,
    auth,
  });
}

/** The `422` `activation-rejected` problem of `error`, or `undefined` for any other error. */
export function activationRejection(error: unknown): ActivationRejected | undefined {
  return problemOf<Schemas['ActivationRejectedProblem']>(error, 'activation-rejected');
}
