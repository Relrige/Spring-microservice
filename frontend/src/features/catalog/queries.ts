import { useQuery } from '@tanstack/react-query';
import { getProduct, listCategories, listProducts } from '../../api/catalog';
import { toProductQuery, type CatalogState } from './url-state';

export const catalogKeys = {
  products: (state: CatalogState) => ['catalog', 'products', state] as const,
  product: (productId: string) => ['catalog', 'product', productId] as const,
  categories: ['catalog', 'categories'] as const,
};

/** EP-CAT-04 for the storefront list; the query key is the parsed URL state (design D8). */
export function useProducts(state: CatalogState) {
  return useQuery({
    queryKey: catalogKeys.products(state),
    queryFn: ({ signal }) => listProducts(toProductQuery(state), { signal }),
  });
}

export function useProduct(productId: string) {
  return useQuery({
    queryKey: catalogKeys.product(productId),
    queryFn: ({ signal }) => getProduct(productId, { signal }),
  });
}

/** EP-CAT-10: all categories by name (the shop has few; one page of 100). */
export function useCategories() {
  return useQuery({
    queryKey: catalogKeys.categories,
    queryFn: ({ signal }) => listCategories({ size: 100 }, { signal }),
    select: (page) => page.content,
    staleTime: 5 * 60_000,
  });
}

export function useCategoryNames(): Map<string, string> | undefined {
  const { data } = useCategories();
  return data ? new Map(data.map((category) => [category.categoryId, category.name])) : undefined;
}
