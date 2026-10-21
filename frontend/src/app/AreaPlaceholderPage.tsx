import { Stack, Text, Title } from '@mantine/core';
import { PageContainer } from './PageContainer';

interface AreaPlaceholderPageProps {
  title: string;
  /** Which iteration (OpenSpec change) adds this area. */
  arrives: string;
}

/** Landing page of an area whose screens come in a later iteration (design D6). */
export function AreaPlaceholderPage({ title, arrives }: AreaPlaceholderPageProps) {
  return (
    <PageContainer>
      <Stack gap="xs">
        <Title order={1}>{title}</Title>
        <Text c="dimmed">{arrives}</Text>
      </Stack>
    </PageContainer>
  );
}
