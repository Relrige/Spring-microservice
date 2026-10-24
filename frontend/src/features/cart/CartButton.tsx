import { ActionIcon, Box } from '@mantine/core';
import { IconShoppingCart } from '@tabler/icons-react';
import { Link, useLocation } from 'react-router';
import { useCart } from './CartProvider';

/** Header cart control with the volt count badge, for visitors and customers (design D7). */
export function CartButton() {
  const { owner, count } = useCart();
  const { pathname } = useLocation();
  if (owner === 'none') return null;
  const current = pathname === '/cart';
  return (
    <Box pos="relative" style={{ flex: 'none' }}>
      <ActionIcon
        component={Link}
        to="/cart"
        size={40}
        radius={6}
        variant="subtle"
        aria-label={count > 0 ? `Cart, ${count} item${count === 1 ? '' : 's'}` : 'Cart'}
        aria-current={current ? 'page' : undefined}
        style={{
          color: current ? 'var(--vs-primary)' : 'var(--vs-ink)',
          background: current ? 'var(--vs-primary-soft)' : undefined,
        }}
      >
        <IconShoppingCart size={22} stroke={1.75} />
      </ActionIcon>
      {count > 0 && (
        <span
          aria-hidden="true"
          data-testid="cart-count"
          style={{
            position: 'absolute',
            top: 2,
            right: 0,
            minWidth: 18,
            height: 18,
            padding: '0 4px',
            boxSizing: 'border-box',
            borderRadius: 9,
            background: 'var(--vs-volt)',
            color: 'var(--vs-on-volt)',
            fontSize: 11,
            lineHeight: '18px',
            fontWeight: 700,
            textAlign: 'center',
            pointerEvents: 'none',
          }}
        >
          {count}
        </span>
      )}
    </Box>
  );
}
