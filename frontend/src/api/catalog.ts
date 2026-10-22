// Catalog Service calls: spec ../docs/design/services-specs/catalog-service-spec.md,
// types generated from ../catalog-service/openapi/public.json (AGENTS.md §3).
import { pathsOf, type JsonBody, type Ok, type Page, type Query, type Received } from './contract';
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
export type ProductDetails = Received<Schemas['ProductDetailsResponse']>;
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
    query: { ...query },
    auth: options.auth ?? 'none',
    signal: options.signal,
  });
}

/** EP-CAT-05 (`getProduct`): product details; `404` for unknown and inactive products. */
export function getProduct(
  productId: string,
  options: { signal?: AbortSignal } = {},
): Promise<ProductDetails> {
  return request<ProductDetails>(pathFor('/catalog/products/{id}', { id: productId }), {
    auth: 'none',
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

/** EP-CAT-01 (`createProduct`): create a product (manager); it starts `NOT_ACTIVE`. */
export function createProduct(
  body: ProductInput,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['createProduct']>>> {
  return request(pathFor('/catalog/products'), { method: 'POST', body, auth });
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
