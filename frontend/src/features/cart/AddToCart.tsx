import { Anchor, Button, Group, NumberInput, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconShoppingCart } from '@tabler/icons-react';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import type { StockStatus } from '../../api/catalog';
import { FormError } from '../../components/form-errors';
import { MAX_QUANTITY } from './cart-rules';
import { useCart } from './CartProvider';

/** Quantity and "Add to cart" on the product page (spec catalog-browsing; design D8). */
export function AddToCart({
  productId,
  stockStatus,
}: {
  productId: string;
  stockStatus: StockStatus;
}) {
  const cart = useCart();
  const navigate = useNavigate();
  const [quantity, setQuantity] = useState<string | number>(1);
  const [message, setMessage] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  if (cart.owner === 'none') return null;

  const add = async () => {
    setMessage(null);
    setAdding(true);
    const result = await cart.add(productId, Number(quantity) || 1);
    setAdding(false);
    if (!result.ok) {
      setMessage(result.message);
      return;
    }
    // The notification renders outside the router, so the link navigates through this page.
    notifications.show({
      title: 'Added to cart',
      message: (
        <Anchor
          href="/cart"
          fw={600}
          onClick={(event) => {
            event.preventDefault();
            notifications.clean();
            navigate('/cart');
          }}
        >
          View cart
        </Anchor>
      ),
    });
  };

  return (
    <Stack gap="sm">
      <Group align="flex-end" gap="md" wrap="wrap">
        <NumberInput
          label="Quantity"
          styles={{ label: { fontSize: 13, fontWeight: 400, color: 'var(--vs-ink-muted)' } }}
          size="md"
          w={120}
          min={1}
          max={MAX_QUANTITY}
          clampBehavior="strict"
          allowDecimal={false}
          allowNegative={false}
          value={quantity}
          onChange={setQuantity}
        />
        <Button
          size="md"
          leftSection={<IconShoppingCart size={20} stroke={1.75} />}
          style={{ flex: '1 1 200px' }}
          disabled={stockStatus === 'OUT_OF_STOCK'}
          loading={adding}
          onClick={() => void add()}
        >
          Add to cart
        </Button>
      </Group>
      <FormError message={message} />
      <Text fz={13} lh="18px" c="dimmed">
        Up to {MAX_QUANTITY} of one product per order. Delivery by Nova Poshta; you enter the
        address at checkout.
      </Text>
    </Stack>
  );
}
