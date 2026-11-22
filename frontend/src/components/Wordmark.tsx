import { Anchor } from '@mantine/core';
import { Link } from 'react-router';

const VOLT_BAR = { borderBottom: '4px solid var(--vs-volt)', paddingBottom: 1 };

/** "VoltStore" in Inter 700 with a volt bar under "Volt" (design-system README, Wordmark). */
export function Wordmark({ size = 20 }: Readonly<{ size?: number }>) {
  return (
    <Anchor
      component={Link}
      to="/"
      underline="never"
      aria-label="VoltStore home"
      style={{ color: 'var(--vs-ink)', fontSize: size, fontWeight: 700, letterSpacing: '-0.01em' }}
    >
      <span style={VOLT_BAR}>Volt</span>Store
    </Anchor>
  );
}
