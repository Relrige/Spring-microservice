// Order queries (change customer-checkout-orders, design D8).
import { useQuery } from '@tanstack/react-query';
import { getOrder, listOrders } from '../../api/order';
import { CHANGING } from './status';

export const orderKeys = {
  all: ['orders'] as const,
  list: (page: number) => ['orders', 'list', page] as const,
  detail: (orderId: string) => ['orders', 'detail', orderId] as const,
};

// BL-13: polling until Order pushes status changes. TanStack Query does not poll hidden tabs.
const POLL_MS = 5_000;

export function useOrder(orderId: string) {
  return useQuery({
    queryKey: orderKeys.detail(orderId),
    queryFn: ({ signal }) => getOrder(orderId, { signal }),
    refetchInterval: (query) =>
      query.state.data && CHANGING.has(query.state.data.status) ? POLL_MS : false,
  });
}

export function useOrders(page: number) {
  return useQuery({
    queryKey: orderKeys.list(page),
    queryFn: ({ signal }) => listOrders({ page, size: 20 }, { signal }),
    refetchInterval: (query) =>
      query.state.data?.content.some((order) => CHANGING.has(order.status)) ? POLL_MS : false,
  });
}
