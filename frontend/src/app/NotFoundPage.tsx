import { Anchor, Stack, Text, Title } from '@mantine/core';
import { Link } from 'react-router';
import { PageContainer } from './PageContainer';

/** Any URL that matches no page (spec app-navigation, Not-found page). */
export function NotFoundPage() {
  return (
    <PageContainer>
      <Stack align="center" gap="sm" py={48} ta="center">
        <Title order={1}>Page not found</Title>
        <Text c="dimmed">The address may be mistyped, or the page has moved.</Text>
        <Anchor component={Link} to="/" fw={600}>
          Go to the catalog
        </Anchor>
      </Stack>
    </PageContainer>
  );
}
