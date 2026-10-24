// Guest cart on login (spec shopping-cart; Customer spec, Client usage; design D6):
// an empty account cart is replaced silently, otherwise the customer chooses
// replace, keep or merge. The guest cart is emptied only after a choice succeeded.
import { Button, Group, List, Modal, Stack, Text } from '@mantine/core';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { getProduct } from '../../api/catalog';
import { cartViolation, getCart, mergeCart, replaceCart, type CartItem } from '../../api/customer';
import { isTransient, userMessage } from '../../api/errors';
import { FormError } from '../../components/form-errors';
import { useSession, type SessionSource } from '../../session/SessionProvider';
import { catalogKeys } from '../catalog/queries';
import { CART_MESSAGES } from './cart-rules';
import { cartQueryKey } from './CartProvider';
import { readGuestCart, useGuestCart, writeGuestCart } from './guest-cart';

type Choice = 'replace' | 'keep' | 'merge';

type State =
  | { kind: 'idle' }
  | { kind: 'working' }
  | { kind: 'asking'; account: CartItem[]; choosing?: Choice; message?: string }
  | { kind: 'failed'; message: string };

/** One run per customer session: remounted when the token changes. */
export function GuestCartResolver() {
  const { session, source } = useSession();
  if (session?.role !== 'CUSTOMER') return null;
  return <Resolver key={session.token} source={source} />;
}

function Resolver({ source }: { source: SessionSource }) {
  const queryClient = useQueryClient();
  const guest = useGuestCart();
  const [state, setState] = useState<State>({ kind: 'idle' });
  // Guard: one start per session, also under StrictMode's double effects.
  const started = useRef(false);

  const finish = useCallback(
    (cart: CartItem[]) => {
      queryClient.setQueryData(cartQueryKey, cart);
      writeGuestCart([]);
      setState({ kind: 'idle' });
    },
    [queryClient],
  );

  /** reading → replacing (account empty) or asking; `message` explains a failed choice. */
  const read = useCallback(
    async (message?: string) => {
      setState({ kind: 'working' });
      try {
        const account = await getCart();
        const items = readGuestCart();
        if (items.length === 0) return setState({ kind: 'idle' });
        if (account.length > 0) return setState({ kind: 'asking', account, message });
        finish(await replaceCart({ items }));
      } catch (error) {
        setState({ kind: 'failed', message: userMessage(error) });
      }
    },
    [finish],
  );

  useEffect(() => {
    // A session from another tab is resolved there; that tab empties the guest cart.
    if (started.current || source === 'other-tab' || guest.length === 0) return;
    started.current = true;
    void read();
  }, [source, guest.length, read]);

  const choose = async (choice: Choice) => {
    if (state.kind !== 'asking') return;
    setState({ ...state, choosing: choice, message: undefined });
    const items = readGuestCart();
    try {
      if (choice === 'keep') finish(state.account);
      else finish(await (choice === 'replace' ? replaceCart({ items }) : mergeCart({ items })));
    } catch (error) {
      const problem = cartViolation(error);
      if (problem)
        setState({ ...state, choosing: undefined, message: CART_MESSAGES[problem.violation] });
      // A lost answer may have been applied (merge is not idempotent): read the account cart again.
      else if (isTransient(error)) void read(userMessage(error));
      else setState({ ...state, choosing: undefined, message: userMessage(error) });
    }
  };

  if (state.kind === 'failed') {
    return (
      <Modal
        opened
        onClose={() => setState({ kind: 'idle' })}
        title="Your cart could not be loaded"
      >
        <Stack gap="md">
          <FormError message={state.message} />
          <Text>The products you added before logging in are kept in this browser.</Text>
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={() => setState({ kind: 'idle' })}>
              Not now
            </Button>
            <Button onClick={() => void read()}>Try again</Button>
          </Group>
        </Stack>
      </Modal>
    );
  }
  if (state.kind !== 'asking') return null;
  return <ChoiceDialog guest={guest} state={state} onChoose={(choice) => void choose(choice)} />;
}

function CartList({ title, items }: { title: string; items: CartItem[] }) {
  const products = useQueries({
    queries: items.map((item) => ({
      queryKey: catalogKeys.product(item.productId),
      queryFn: ({ signal }: { signal: AbortSignal }) => getProduct(item.productId, { signal }),
      retry: false,
    })),
  });
  return (
    <Stack gap={6} aria-label={title} role="group">
      <Text fw={600}>{title}</Text>
      <List size="sm" spacing={4}>
        {items.map((item, index) => {
          const query = products[index]!;
          const name =
            query.data?.title ?? (query.isError ? 'Product no longer available' : 'Loading…');
          return (
            <List.Item key={item.productId}>
              {item.quantity} × {name}
            </List.Item>
          );
        })}
      </List>
    </Stack>
  );
}

function ChoiceDialog({
  guest,
  state,
  onChoose,
}: {
  guest: CartItem[];
  state: Extract<State, { kind: 'asking' }>;
  onChoose: (choice: Choice) => void;
}) {
  const busy = state.choosing !== undefined;
  const button = (choice: Choice) => ({
    loading: state.choosing === choice,
    disabled: busy,
    onClick: () => onChoose(choice),
  });
  return (
    // Spec shopping-cart, Replace, keep or merge choice: open until a choice succeeds.
    <Modal
      opened
      onClose={() => {}}
      withCloseButton={false}
      closeOnEscape={false}
      closeOnClickOutside={false}
      title="You have products in two carts"
      size="lg"
    >
      <Stack gap="md">
        <Text>Choose what to do with the products you added before logging in.</Text>
        <FormError message={state.message ?? null} />
        <Group align="flex-start" gap="xl" wrap="wrap">
          <CartList title="In this browser" items={guest} />
          <CartList title="In your account" items={state.account} />
        </Group>
        <Group justify="flex-end" gap="sm" wrap="wrap">
          <Button variant="default" {...button('keep')}>
            Keep account cart
          </Button>
          <Button variant="default" {...button('replace')}>
            Replace with this browser&apos;s cart
          </Button>
          <Button {...button('merge')}>Merge both</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
