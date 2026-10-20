// Catalog list state kept in the URL (spec catalog-browsing, design D8).
import type { ProductQuery, SortBy } from '../../api/catalog';

export const PAGE_SIZE = 20;

export const SORT_OPTIONS: { value: SortBy; label: string }[] = [
  { value: 'title,asc', label: 'Name A–Z' },
  { value: 'title,desc', label: 'Name Z–A' },
  { value: 'basePrice,asc', label: 'Price low to high' },
  { value: 'basePrice,desc', label: 'Price high to low' },
];

export interface CatalogState {
  search: string;
  /** Category UUID. */
  category: string | null;
  minPrice: number | null;
  maxPrice: number | null;
  sort: SortBy;
  /** 1-based for people; sent to the API as `page - 1`. */
  page: number;
}

export const DEFAULT_STATE: CatalogState = {
  search: '',
  category: null,
  minPrice: null,
  maxPrice: null,
  sort: 'title,asc',
  page: 1,
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function parsePrice(value: string | null): number | null {
  if (value === null || value.trim() === '') return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : null;
}

/** Turns a query string into a valid state; missing or invalid values fall back to the defaults. */
export function parseCatalogState(params: URLSearchParams): CatalogState {
  const search = params.get('search')?.trim() ?? '';
  const category = params.get('category');
  let minPrice = parsePrice(params.get('minPrice'));
  let maxPrice = parsePrice(params.get('maxPrice'));
  if (minPrice !== null && maxPrice !== null && minPrice > maxPrice) {
    minPrice = null;
    maxPrice = null;
  }
  const sort = params.get('sort');
  const page = params.get('page');
  return {
    search,
    category: category && UUID.test(category) ? category.toLowerCase() : null,
    minPrice,
    maxPrice,
    sort: SORT_OPTIONS.some((option) => option.value === sort)
      ? (sort as SortBy)
      : DEFAULT_STATE.sort,
    page: page && /^\d+$/.test(page) && Number(page) >= 1 ? Number(page) : DEFAULT_STATE.page,
  };
}

/** The query string for a state, leaving out defaults so URLs stay short. */
export function serializeCatalogState(state: CatalogState): URLSearchParams {
  const params = new URLSearchParams();
  if (state.search) params.set('search', state.search);
  if (state.category) params.set('category', state.category);
  if (state.minPrice !== null) params.set('minPrice', String(state.minPrice));
  if (state.maxPrice !== null) params.set('maxPrice', String(state.maxPrice));
  if (state.sort !== DEFAULT_STATE.sort) params.set('sort', state.sort);
  if (state.page !== DEFAULT_STATE.page) params.set('page', String(state.page));
  return params;
}

/** The EP-CAT-04 query for a state. */
export function toProductQuery(state: CatalogState): ProductQuery {
  return {
    search: state.search || undefined,
    categoryId: state.category ?? undefined,
    minPrice: state.minPrice ?? undefined,
    maxPrice: state.maxPrice ?? undefined,
    sortBy: state.sort,
    page: state.page - 1,
    size: PAGE_SIZE,
  };
}

/** The URL of the list with the given state. */
export function catalogUrl(state: CatalogState): string {
  const query = serializeCatalogState(state).toString();
  return query ? `/?${query}` : '/';
}
