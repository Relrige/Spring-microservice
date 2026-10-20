// Catalog Service calls, mirroring ../docs/design/services-specs/catalog-service-spec.md.
import { request, type RequestOptions } from './http';

type Auth = RequestOptions['auth'];

/** Spec "Service Overview": `ProductStatus`. */
export type ProductStatus = 'ACTIVE' | 'NOT_ACTIVE';
/** Spec "Service Overview": `StockStatus` (read model updated from Inventory events). */
export type StockStatus = 'IN_STOCK' | 'OUT_OF_STOCK';

/** EP-CAT-04 and EP-CAT-10 paging envelope: `{ content, page, totalElements }`, page zero-based. */
export interface Page<T> {
  content: T[];
  page: number;
  totalElements: number;
}

/** EP-CAT-10 item: `{ categoryId, name }`. */
export interface Category {
  categoryId: string;
  name: string;
}

/** EP-CAT-04 item: `{ productId, title, basePrice, categoryId, status, stockStatus }`. */
export interface ProductSummary {
  productId: string;
  title: string;
  basePrice: number;
  categoryId: string;
  status: ProductStatus;
  stockStatus: StockStatus;
}

/** EP-CAT-05: `{ productId, title, description, categoryId, basePrice, stockStatus }`. */
export interface ProductDetails {
  productId: string;
  title: string;
  description: string;
  categoryId: string;
  basePrice: number;
  stockStatus: StockStatus;
}

export type SortBy = 'title,asc' | 'title,desc' | 'basePrice,asc' | 'basePrice,desc';

/** EP-CAT-04 query parameters (`page` zero-based, `size` default 20, max 100). */
export interface ProductQuery {
  search?: string;
  categoryId?: string;
  minPrice?: number;
  maxPrice?: number;
  sortBy?: SortBy;
  page?: number;
  size?: number;
  /** Manager only; ignored for other callers. */
  status?: ProductStatus;
}

/** EP-CAT-01 and EP-CAT-02 body. */
export interface ProductInput {
  title: string;
  description: string;
  categoryId: string;
  basePrice: number;
}

/** BigDecimal may be serialized as a JSON number or string (design, Open Questions). */
export function toPrice(value: number | string): number {
  return typeof value === 'number' ? value : Number(value);
}

type Wire<T extends { basePrice: number }> = Omit<T, 'basePrice'> & { basePrice: number | string };

function withPrice<T extends { basePrice: number }>(item: Wire<T>): T {
  return { ...item, basePrice: toPrice(item.basePrice) } as T;
}

/**
 * EP-CAT-04: browse products. The storefront sends no token (`auth: 'none'`,
 * spec catalog-browsing) so a logged-in manager still sees the customer view.
 */
export async function listProducts(
  query: ProductQuery,
  options: { auth?: Auth; signal?: AbortSignal } = {},
): Promise<Page<ProductSummary>> {
  const page = await request<Page<Wire<ProductSummary>>>('/catalog/products', {
    query: { ...query },
    auth: options.auth ?? 'none',
    signal: options.signal,
  });
  return { ...page, content: page.content.map(withPrice<ProductSummary>) };
}

/** EP-CAT-05: product details; `404` for unknown and inactive products. */
export async function getProduct(
  productId: string,
  options: { signal?: AbortSignal } = {},
): Promise<ProductDetails> {
  const product = await request<Wire<ProductDetails>>(
    `/catalog/products/${encodeURIComponent(productId)}`,
    { auth: 'none', signal: options.signal },
  );
  return withPrice<ProductDetails>(product);
}

/** EP-CAT-10: categories ordered by name. */
export function listCategories(
  query: { search?: string; page?: number; size?: number } = {},
  options: { auth?: Auth; signal?: AbortSignal } = {},
): Promise<Page<Category>> {
  return request<Page<Category>>('/catalog/categories', {
    query: { ...query },
    auth: options.auth ?? 'none',
    signal: options.signal,
  });
}

/** EP-CAT-06: create a category (manager). `409` = name in use. */
export function createCategory(name: string, auth: Auth = 'session'): Promise<Category> {
  return request<Category>('/catalog/categories', { method: 'POST', body: { name }, auth });
}

/** EP-CAT-01: create a product (manager); it starts `NOT_ACTIVE`. */
export function createProduct(
  body: ProductInput,
  auth: Auth = 'session',
): Promise<{ productId: string }> {
  return request<{ productId: string }>('/catalog/products', { method: 'POST', body, auth });
}

/** EP-CAT-03: activate or deactivate (manager). `422 { reason }` when not ready, `503` when Inventory is down. */
export function setProductStatus(
  productId: string,
  status: ProductStatus,
  auth: Auth = 'session',
): Promise<unknown> {
  return request(`/catalog/products/${encodeURIComponent(productId)}/status`, {
    method: 'PATCH',
    body: { status },
    auth,
  });
}
