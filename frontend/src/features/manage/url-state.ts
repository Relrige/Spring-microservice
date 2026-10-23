// Manager list state kept in the URL (change catalog-manager-console, design D5, D9).
// The parse rules are the storefront's: invalid values fall back, defaults stay out of the URL.
import type { ProductQuery, ProductStatus, SortBy } from '../../api/catalog';
import { parseCatalogState, PAGE_SIZE } from '../catalog/url-state';

/** The status filter values of `browseProducts`; `null` = all statuses. */
export const STATUS_OPTIONS: { value: ProductStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'NOT_ACTIVE', label: 'Inactive' },
];

export interface ManagedProductsState {
  search: string;
  /** Category UUID. */
  category: string | null;
  status: ProductStatus | null;
  sort: SortBy;
  /** 1-based; sent to the API as `page - 1`. */
  page: number;
}

export const DEFAULT_PRODUCTS_STATE: ManagedProductsState = {
  search: '',
  category: null,
  status: null,
  sort: 'title,asc',
  page: 1,
};

export function parseProductsState(params: URLSearchParams): ManagedProductsState {
  const { search, category, sort, page } = parseCatalogState(params);
  const status = params.get('status');
  return {
    search,
    category,
    status: STATUS_OPTIONS.some((option) => option.value === status)
      ? (status as ProductStatus)
      : null,
    sort,
    page,
  };
}

export function serializeProductsState(state: ManagedProductsState): URLSearchParams {
  const params = new URLSearchParams();
  if (state.search) params.set('search', state.search);
  if (state.category) params.set('category', state.category);
  if (state.status) params.set('status', state.status);
  if (state.sort !== DEFAULT_PRODUCTS_STATE.sort) params.set('sort', state.sort);
  if (state.page !== DEFAULT_PRODUCTS_STATE.page) params.set('page', String(state.page));
  return params;
}

/** The EP-CAT-04 query; sent with the manager's token, so `status` is honoured. */
export function toManagedProductQuery(state: ManagedProductsState): ProductQuery {
  return {
    search: state.search || undefined,
    categoryId: state.category ?? undefined,
    status: state.status ?? undefined,
    sortBy: state.sort,
    page: state.page - 1,
    size: PAGE_SIZE,
  };
}

export interface CategoriesState {
  search: string;
  /** 1-based. */
  page: number;
}

export const DEFAULT_CATEGORIES_STATE: CategoriesState = { search: '', page: 1 };

export function parseCategoriesState(params: URLSearchParams): CategoriesState {
  const { search, page } = parseCatalogState(params);
  return { search, page };
}

export function serializeCategoriesState(state: CategoriesState): URLSearchParams {
  const params = new URLSearchParams();
  if (state.search) params.set('search', state.search);
  if (state.page !== DEFAULT_CATEGORIES_STATE.page) params.set('page', String(state.page));
  return params;
}

/** The EP-CAT-10 query. */
export function toCategoryQuery(state: CategoriesState) {
  return { search: state.search || undefined, page: state.page - 1, size: PAGE_SIZE };
}
