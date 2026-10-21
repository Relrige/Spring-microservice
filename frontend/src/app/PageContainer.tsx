import { Box, type BoxProps } from '@mantine/core';
import type { ReactNode } from 'react';

/** Page content centred at content-max with the token gutters. */
export function PageContainer({ children, ...props }: BoxProps & { children: ReactNode }) {
  return (
    <Box
      maw="var(--vs-content-max)"
      mx="auto"
      px={{ base: 'md', sm: 'lg' }}
      pt={{ base: 'md', sm: 'xl' }}
      pb={48}
      {...props}
    >
      {children}
    </Box>
  );
}
