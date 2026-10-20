import { render, type RenderOptions } from '@testing-library/react';
import type { ReactElement } from 'react';
import { Providers } from '../app/Providers';
import { createQueryClient } from '../app/query-client';

/** Renders inside the app providers (Mantine in test mode, a fresh query client). */
export function renderWithProviders(ui: ReactElement, options?: RenderOptions) {
  const queryClient = createQueryClient({ retryDelay: 0 });
  return {
    queryClient,
    ...render(
      <Providers env="test" queryClient={queryClient}>
        {ui}
      </Providers>,
      options,
    ),
  };
}
