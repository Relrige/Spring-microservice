// Worker queries (change inventory-worker-console, design D3, D7).
import { useQuery } from '@tanstack/react-query';
import { getFulfillmentQueue } from '../../api/order';

export const queueKey = ['fulfillment-queue'] as const;

/** EP-ORD-07; `refetchInterval` is 5 s on the queue page (BL-13) and 30 s for the header count. */
export function useQueue(options: { enabled?: boolean; refetchInterval: number }) {
  return useQuery({
    queryKey: queueKey,
    queryFn: ({ signal }) => getFulfillmentQueue({ signal }),
    refetchOnWindowFocus: true,
    ...options,
  });
}
