import { QueryClient } from '@tanstack/react-query';
import { isTransient } from '../api/errors';

/**
 * Design D3: reads retry once, and only for network errors and 502/503/504;
 * mutations are never retried automatically (spec api-access, Unavailable backend).
 */
export function createQueryClient(options: { retryDelay?: number } = {}): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => failureCount < 1 && isTransient(error),
        retryDelay: options.retryDelay ?? 1000,
        staleTime: 30_000,
        refetchOnWindowFocus: false,
      },
      mutations: { retry: false },
    },
  });
}
