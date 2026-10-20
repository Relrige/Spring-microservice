import { Box, Paper, Stack, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';

/** The centred card of the login and registration pages (mockup Login.dc.html). */
export function AuthCard({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: string;
  children: ReactNode;
}) {
  return (
    <Box
      px="md"
      pt={{ base: 32, sm: 64 }}
      pb={48}
      style={{ display: 'flex', justifyContent: 'center' }}
    >
      <Paper
        w="100%"
        maw={400}
        p={{ base: 'lg', sm: 32 }}
        radius="md"
        className="vs-raised"
        style={{ boxSizing: 'border-box' }}
      >
        <Stack gap={20}>
          <Stack gap={4}>
            <Title order={1}>{title}</Title>
            <Text c="dimmed">{subtitle}</Text>
          </Stack>
          {children}
        </Stack>
      </Paper>
    </Box>
  );
}
