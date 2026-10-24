// Live product data of cart lines (change customer-cart, design D5), shared by the
// cart page and the checkout (change customer-checkout-orders, design D5).
import { useQueries, type UseQueryResult } from '@tanstack/react-query';
import { getProduct, type ProductDetails } from '../../api/catalog';
import type { CartItem } from '../../api/customer';
import { ApiError } from '../../api/errors';
import { catalogKeys } from '../catalog/queries';

export type LineView =
  | { kind: 'loading' }
  | { kind: 'failed'; retry: () => void }
  | { kind: 'gone' }
  | { kind: 'sold-out'; product: ProductDetails }
  | { kind: 'available'; product: ProductDetails };

function viewOf(query: UseQueryResult<ProductDetails>): LineView {
  if (query.data) {
    return query.data.stockStatus === 'OUT_OF_STOCK'
      ? { kind: 'sold-out', product: query.data }
      : { kind: 'available', product: query.data };
  }
  if (query.isError) {
    // Inactive and deleted products answer 404 (EP-CAT-05).
    return query.error instanceof ApiError && query.error.kind === 'not-found'
      ? { kind: 'gone' }
      : { kind: 'failed', retry: () => void query.refetch() };
  }
  return { kind: 'loading' };
}

/** One view per line, in the order of `lines`. */
export function useLineViews(lines: CartItem[]): LineView[] {
  return useQueries({
    queries: lines.map((line) => ({
      queryKey: catalogKeys.product(line.productId),
      queryFn: ({ signal }: { signal: AbortSignal }) => getProduct(line.productId, { signal }),
    })),
  }).map(viewOf);
}

/** The available lines with their prices, for the estimated subtotal. */
export function availableLines(lines: CartItem[], views: LineView[]) {
  return lines.flatMap((line, index) => {
    const view = views[index];
    return view?.kind === 'available'
      ? [{ ...line, title: view.product.title, price: view.product.basePrice }]
      : [];
  });
}
