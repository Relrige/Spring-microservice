// `/cart` (spec shopping-cart; mockup Cart): lines with live product data, the summary
// and the checkout entry. The cart holds only ids and quantities (design D5).
import { ActionIcon, Anchor, Box, Button, Group, Modal, Stack, Text, Title } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import { useQueries, useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { getProduct, type ProductDetails } from '../../api/catalog';
import type { CartItem } from '../../api/customer';
import { ApiError } from '../../api/errors';
import { AccessDeniedPage } from '../../app/AccessDeniedPage';
import { PageContainer } from '../../app/PageContainer';
import { ErrorState } from '../../components/ErrorState';
import { FormError } from '../../components/form-errors';
import { Loading } from '../../components/Loading';
import { formatPrice } from '../../components/Price';
import { ProductImage } from '../../components/ProductImage';
import { StatusChip } from '../../components/StatusChip';
import { loginUrl } from '../../session/roles';
import { catalogKeys, useCategoryNames } from '../catalog/queries';
import { MAX_PRODUCTS, MAX_QUANTITY } from './cart-rules';
import { cartQuery, useCart, type CartChange } from './CartProvider';
import { estimatedSubtotal, lineKopecks } from './subtotal';

type LineView =
  | { kind: 'loading' }
  | { kind: 'failed'; retry: () => void }
  | { kind: 'gone' }
  | { kind: 'sold-out'; product: ProductDetails }
  | { kind: 'available'; product: ProductDetails };

function viewOf(query: UseQueryResult<ProductDetails>): LineView {
  if (query.data) {
    return query.data.stockStatus === 'OUT_OF_STOCK'
      ? { kind: 'sold-out', product: query.data }
      : { kind: 'available', product: query.data };
  }
  if (query.isError) {
    // Inactive and deleted products answer 404 (EP-CAT-05).
    return query.error instanceof ApiError && query.error.kind === 'not-found'
      ? { kind: 'gone' }
      : { kind: 'failed', retry: () => void query.refetch() };
  }
  return { kind: 'loading' };
}

const ROW = {
  padding: 20,
  display: 'flex',
  flexWrap: 'wrap',
  gap: 16,
  alignItems: 'center',
} as const;

const muted = { fontSize: 13, lineHeight: '18px', color: 'var(--vs-ink-muted)' };

interface LineProps {
  line: CartItem;
  view: LineView;
  categoryName: string | undefined;
  busy: boolean;
  last: boolean;
  onQuantity: (quantity: number) => void;
  onRemove: () => void;
}

function Stepper({
  quantity,
  disabled,
  onChange,
}: {
  quantity: number;
  disabled: boolean;
  onChange: (quantity: number) => void;
}) {
  return (
    <Group
      gap={0}
      wrap="nowrap"
      h={40}
      style={{ border: '1px solid var(--vs-line-strong)', borderRadius: 6 }}
    >
      <Button
        variant="subtle"
        color="dark"
        w={40}
        h={38}
        p={0}
        aria-label="Decrease quantity"
        disabled={disabled || quantity <= 1}
        onClick={() => onChange(quantity - 1)}
      >
        −
      </Button>
      <Text w={32} ta="center" fw={600} data-testid="line-quantity">
        {quantity}
      </Text>
      <Button
        variant="subtle"
        color="dark"
        w={40}
        h={38}
        p={0}
        aria-label="Increase quantity"
        disabled={disabled || quantity >= MAX_QUANTITY}
        onClick={() => onChange(quantity + 1)}
      >
        +
      </Button>
    </Group>
  );
}

function CartLine({ line, view, categoryName, busy, last, onQuantity, onRemove }: LineProps) {
  const product = view.kind === 'available' || view.kind === 'sold-out' ? view.product : undefined;
  const unavailable = view.kind === 'gone' || view.kind === 'sold-out';
  const removeButton = (
    <Button variant="default" onClick={onRemove} disabled={busy}>
      Remove
    </Button>
  );
  return (
    <Box
      data-testid="cart-line"
      style={{
        ...ROW,
        borderBottom: last ? undefined : '1px solid var(--vs-line)',
        background: unavailable ? 'var(--vs-surface)' : undefined,
      }}
    >
      <ProductImage categoryName={view.kind === 'gone' ? null : categoryName} size={72} />
      <Stack gap={6} style={{ flex: '1 1 220px', minWidth: 0 }}>
        {view.kind === 'gone' && (
          <Text fw={600} c="dimmed">
            Product no longer available
          </Text>
        )}
        {view.kind === 'loading' && <Text c="dimmed">Loading product…</Text>}
        {view.kind === 'failed' && (
          <Group gap="sm">
            <Text c="var(--vs-danger)">Product details could not be loaded</Text>
            <Anchor component="button" type="button" fw={600} onClick={view.retry}>
              Try again
            </Anchor>
          </Group>
        )}
        {product && (
          <Anchor
            component={Link}
            to={`/products/${product.productId}`}
            fw={600}
            c={unavailable ? 'dimmed' : 'var(--vs-ink)'}
            underline="hover"
          >
            {product.title}
          </Anchor>
        )}
        {unavailable ? (
          <Box style={{ alignSelf: 'flex-start' }}>
            <StatusChip tone="warning">Unavailable</StatusChip>
          </Box>
        ) : (
          product && <span style={muted}>{formatPrice(product.basePrice)} each</span>
        )}
      </Stack>
      {unavailable ? (
        <>
          <span style={muted}>Not counted in the subtotal</span>
          {removeButton}
        </>
      ) : (
        <>
          <Stepper quantity={line.quantity} disabled={busy} onChange={onQuantity} />
          {view.kind === 'available' ? (
            <Text w={140} ta="right" fw={700} className="vs-price">
              {formatPrice(lineKopecks(view.product.basePrice, line.quantity) / 100)}
            </Text>
          ) : null}
          {product ? (
            <ActionIcon
              variant="subtle"
              color="gray"
              size={40}
              aria-label={`Remove ${product.title}`}
              disabled={busy}
              onClick={onRemove}
            >
              <IconTrash size={20} stroke={1.75} />
            </ActionIcon>
          ) : (
            removeButton
          )}
        </>
      )}
    </Box>
  );
}

export function CartPage() {
  const cart = useCart();
  // Spec shopping-cart, Cart opened on a second device: read the server cart again.
  useQuery({ ...cartQuery, enabled: cart.owner === 'customer', refetchOnMount: 'always' });
  const categoryNames = useCategoryNames();
  const products = useQueries({
    queries: cart.lines.map((line) => ({
      queryKey: catalogKeys.product(line.productId),
      queryFn: ({ signal }: { signal: AbortSignal }) => getProduct(line.productId, { signal }),
    })),
  });
  const [message, setMessage] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  if (cart.owner === 'none') return <AccessDeniedPage />;

  const run = async (change: Promise<CartChange>) => {
    setMessage(null);
    const result = await change;
    if (!result.ok) setMessage(result.message);
  };

  let body;
  if (cart.status === 'loading') {
    body = <Loading lines={4} label="Loading your cart…" />;
  } else if (cart.status === 'error') {
    body = <ErrorState error={cart.error} onRetry={cart.reload} />;
  } else if (cart.lines.length === 0) {
    body = (
      <Stack align="center" gap="sm" py={48} ta="center">
        <Title order={2}>Your cart is empty</Title>
        <Text c="dimmed">Products you add appear here.</Text>
        <Anchor component={Link} to="/" fw={600}>
          Browse the catalog
        </Anchor>
      </Stack>
    );
  } else {
    const views = products.map(viewOf);
    const available = cart.lines.flatMap((line, index) => {
      const view = views[index]!;
      return view.kind === 'available'
        ? [{ price: view.product.basePrice, quantity: line.quantity }]
        : [];
    });
    const incomplete = views.some((view) => view.kind === 'loading' || view.kind === 'failed');
    const items = available.reduce((sum, line) => sum + line.quantity, 0);
    body = (
      <Group align="flex-start" gap="lg" wrap="wrap">
        <Box
          component="section"
          aria-label="Cart items"
          style={{
            flex: '999 1 560px',
            minWidth: 0,
            background: 'var(--vs-surface-raised)',
            border: '1px solid var(--vs-line)',
            borderRadius: 10,
            overflow: 'hidden',
          }}
        >
          {cart.lines.map((line, index) => {
            const view = views[index]!;
            const product = 'product' in view ? view.product : undefined;
            return (
              <CartLine
                key={line.productId}
                line={line}
                view={view}
                categoryName={product && categoryNames?.get(product.categoryId)}
                busy={cart.busy(line.productId)}
                last={index === cart.lines.length - 1}
                onQuantity={(quantity) => void run(cart.setQuantity(line.productId, quantity))}
                onRemove={() => void run(cart.remove(line.productId))}
              />
            );
          })}
        </Box>
        <Stack
          component="aside"
          aria-label="Order summary"
          gap="md"
          p="lg"
          style={{
            flex: '1 1 320px',
            maxWidth: '100%',
            background: 'var(--vs-surface-raised)',
            border: '1px solid var(--vs-line)',
            borderRadius: 16,
          }}
        >
          <Title order={2}>Summary</Title>
          <Group justify="space-between" gap="sm">
            <Text c="dimmed">Items</Text>
            <Text data-testid="summary-items">{items}</Text>
          </Group>
          <Group justify="space-between" gap="sm" align="baseline">
            <Text fw={600}>Estimated subtotal</Text>
            <Text fz={20} lh="24px" fw={700} className="vs-price" data-testid="summary-subtotal">
              {formatPrice(estimatedSubtotal(available))}
            </Text>
          </Group>
          {incomplete && (
            <Text style={muted} c="var(--vs-warning)">
              The subtotal is incomplete: some product details are not loaded yet.
            </Text>
          )}
          <Text style={muted}>
            Prices and availability are checked again when you check out. Delivery cost is paid to
            the carrier.
          </Text>
          {/* Design D9: a stub until checkout arrives in iteration 3. */}
          {cart.owner === 'guest' && items > 0 ? (
            <Button component={Link} to={loginUrl('/cart')} size="md">
              Proceed to checkout
            </Button>
          ) : (
            <Button size="md" disabled>
              Proceed to checkout
            </Button>
          )}
          {cart.owner === 'customer' && (
            <Text style={muted}>Checkout arrives in the next iteration.</Text>
          )}
          <Text style={muted}>
            Up to {MAX_PRODUCTS} different products, up to {MAX_QUANTITY} of each.
          </Text>
        </Stack>
      </Group>
    );
  }

  return (
    <PageContainer>
      <Stack gap="lg">
        <Group justify="space-between" wrap="wrap">
          <Title order={1}>Cart</Title>
          {cart.status === 'ready' && cart.lines.length > 0 && (
            <Button
              variant="subtle"
              color="gray"
              disabled={cart.busy('*')}
              onClick={() => setConfirming(true)}
            >
              Clear cart
            </Button>
          )}
        </Group>
        <FormError message={message} />
        {body}
      </Stack>
      <Modal opened={confirming} onClose={() => setConfirming(false)} title="Clear the cart?">
        <Stack gap="md">
          <Text>Every product is removed from your cart.</Text>
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button
              color="red"
              onClick={() => {
                setConfirming(false);
                void run(cart.clear());
              }}
            >
              Clear cart
            </Button>
          </Group>
        </Stack>
      </Modal>
    </PageContainer>
  );
}
