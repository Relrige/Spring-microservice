import { Group, Text } from '@mantine/core';
import { ColorSchemeToggle } from '../components/ColorSchemeToggle';
import { Providers } from './Providers';

export function App() {
  return (
    <Providers>
      <Group p="md" justify="space-between">
        <Text fw={700} fz={20}>
          VoltStore
        </Text>
        <ColorSchemeToggle />
      </Group>
    </Providers>
  );
}
