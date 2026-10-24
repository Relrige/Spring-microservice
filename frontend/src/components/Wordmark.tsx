import { Anchor } from '@mantine/core';
import { Link } from 'react-router';

/** "VoltStore" in Inter 700 with a volt bar under "Volt" (design-system README, Wordmark). */
export function Wordmark({ size = 20 }: { size?: number }) {
  return (
    <Anchor
      component={Link}
      to="/"
      underline="never"
      aria-label="VoltStore home"
      style={{ color: 'var(--vs-ink)', fontSize: size, fontWeight: 700, letterSpacing: '-0.01em' }}
    >
      <span style={{ borderBottom: '4px solid var(--vs-volt)', paddingBottom: 1 }}>Volt</span>
      Store
    </Anchor>
  );
}
