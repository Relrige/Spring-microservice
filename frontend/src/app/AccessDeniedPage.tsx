import { Anchor, Stack, Text, Title } from '@mantine/core';
import { Link } from 'react-router';
import { roleHome, ROLE_LABELS } from '../session/roles';
import { useSession } from '../session/SessionProvider';
import { PageContainer } from './PageContainer';

/** A logged-in user opened another role's area (spec app-navigation, Wrong role). */
export function AccessDeniedPage() {
  const { session } = useSession();
  const home = session ? roleHome(session.role) : '/';
  const label =
    home === '/'
      ? 'Back to the catalog'
      : `Go to the ${ROLE_LABELS[session!.role].toLowerCase()} area`;
  return (
    <PageContainer>
      <Stack align="center" gap="sm" py={48} ta="center">
        <Title order={1}>You do not have access to this page</Title>
        <Text c="dimmed">This part of VoltStore belongs to another role.</Text>
        <Anchor component={Link} to={home} fw={600}>
          {label}
        </Anchor>
      </Stack>
    </PageContainer>
  );
}
