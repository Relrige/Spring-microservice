// Manager reads under their own key root (design D3): staff results, read with the
// manager's token, never share a cache entry with the storefront's `['catalog', …]`.
import { useQuery, type QueryClient } from '@tanstack/react-query';
import { getProduct, listCategories, listProducts } from '../../api/catalog';
import {
  toCategoryQuery,
  toManagedProductQuery,
  type CategoriesState,
  type ManagedProductsState,
} from './url-state';

export const manageKeys = {
  all: ['manage'] as const,
  products: (state: ManagedProductsState) => ['manage', 'products', state] as const,
  product: (productId: string) => ['manage', 'product', productId] as const,
  categories: (state: CategoriesState) => ['manage', 'categories', state] as const,
};

export function useManagedProducts(state: ManagedProductsState) {
  return useQuery({
    queryKey: manageKeys.products(state),
    queryFn: ({ signal }) =>
      listProducts(toManagedProductQuery(state), { auth: 'session', signal }),
  });
}

/** EP-CAT-05 with the manager's token: inactive products load too (BL-01). */
export function useManagedProduct(productId: string) {
  return useQuery({
    queryKey: manageKeys.product(productId),
    queryFn: ({ signal }) => getProduct(productId, { auth: 'session', signal }),
  });
}

export function useManagedCategories(state: CategoriesState) {
  return useQuery({
    queryKey: manageKeys.categories(state),
    queryFn: ({ signal }) => listCategories(toCategoryQuery(state), { signal }),
  });
}

/** After every successful manager write: refresh the manager's and the storefront's catalog reads. */
export function invalidateCatalog(queryClient: QueryClient): Promise<unknown> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: manageKeys.all }),
    queryClient.invalidateQueries({ queryKey: ['catalog'] }),
  ]);
}
