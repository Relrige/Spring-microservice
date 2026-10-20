import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '../theme/global.css';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { cssVariablesResolver, theme } from '../theme/theme';
import { createQueryClient } from './query-client';

interface ProvidersProps {
  children: ReactNode;
  queryClient?: QueryClient;
  /** `test` disables transitions and portals (Mantine). */
  env?: 'default' | 'test';
}

export function Providers({ children, queryClient, env = 'default' }: ProvidersProps) {
  const [client] = useState(() => queryClient ?? createQueryClient());
  return (
    <QueryClientProvider client={client}>
      <MantineProvider
        theme={theme}
        cssVariablesResolver={cssVariablesResolver}
        defaultColorScheme="auto"
        env={env}
      >
        <Notifications position="top-right" />
        {children}
      </MantineProvider>
    </QueryClientProvider>
  );
}
