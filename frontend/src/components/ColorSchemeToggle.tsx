import { ActionIcon, useComputedColorScheme, useMantineColorScheme } from '@mantine/core';
import { IconMoon, IconSun } from '@tabler/icons-react';

/** Switches between light and dark; the default follows the system setting. */
export function ColorSchemeToggle() {
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('light', { getInitialValueInEffect: false });
  const next = computed === 'dark' ? 'light' : 'dark';
  return (
    <ActionIcon
      variant="subtle"
      color="gray"
      size={40}
      aria-label={`Switch to ${next} theme`}
      onClick={() => setColorScheme(next)}
    >
      {computed === 'dark' ? (
        <IconSun size={20} stroke={1.75} />
      ) : (
        <IconMoon size={20} stroke={1.75} />
      )}
    </ActionIcon>
  );
}
