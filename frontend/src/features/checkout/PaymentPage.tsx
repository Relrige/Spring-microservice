// `/checkout/:orderId`: the payment step (spec checkout; mockup Checkout; design D7).
import { Alert, Anchor, Box, Button, Group, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError, userMessage } from '../../api/errors';
import { orderProblem, payOrder, type OrderDetails } from '../../api/order';
import { PageContainer } from '../../app/PageContainer';
import { formatTime, ShortId } from '../../components/DateTime';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { formatPrice } from '../../components/Price';
import { cartQueryKey } from '../cart/CartProvider';
import { lineKopecks } from '../cart/subtotal';
import { orderKeys, useOrder } from '../orders/queries';
import { ORDER_STATUS } from '../orders/status';
import { cardNumberError, cvvError, expiryError, toCardDetails } from './card';
import { CARRIERS, StepIndicator } from './CheckoutPage';

const TEST_CARDS = [
  ['… 4242', 'Approved'],
  ['… 0002', 'Declined, insufficient funds'],
  ['… 5000', 'Answer lost; pay again with the same card'],
  ['any other', 'Declined'],
];

const PAID = new Set(['PAID', 'PENDING_SHIPPING', 'SHIPPED', 'DELIVERED']);

/** The current time, updated every `ms`. */
function useNow(ms: number): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(timer);
  }, [ms]);
  return now;
}

const card = {
  background: 'var(--vs-surface-raised)',
  border: '1px solid var(--vs-line)',
  borderRadius: 10,
} as const;

function Notice({ children }: { children: ReactNode }) {
  return (
    <Stack align="flex-start" gap="sm" p="lg" style={card}>
      {children}
    </Stack>
  );
}

function Summary({ order }: { order: OrderDetails }) {
  return (
    <Stack
      component="aside"
      aria-label="Order summary"
      gap="sm"
      p="lg"
      style={{ ...card, flex: '1 1 320px', maxWidth: '100%', borderRadius: 16 }}
    >
      <Title order={2}>Your order</Title>
      {order.items.map((item) => (
        <Group key={item.productId} justify="space-between" gap="sm" wrap="nowrap">
          <Text fz={14}>
            {item.productTitle} × {item.quantity}
          </Text>
          <Text fz={14} className="vs-price">
            {formatPrice(lineKopecks(item.unitPriceAtOrder, item.quantity) / 100)}
          </Text>
        </Group>
      ))}
      <Group justify="space-between" pt="sm" style={{ borderTop: '1px solid var(--vs-line)' }}>
        <Text fw={600}>Total</Text>
        <Text fw={700} className="vs-price">
          {formatPrice(order.totalAmount)}
        </Text>
      </Group>
      <Text fz={13} c="dimmed">
        Prices were fixed when the order was placed.
      </Text>
    </Stack>
  );
}

export function PaymentPage() {
  const { orderId = '' } = useParams();
  const order = useOrder(orderId);
  const now = useNow(1000);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [windowClosed, setWindowClosed] = useState(false);
  const [paying, setPaying] = useState(false);
  // Card data live only in this form's state: never in the URL, storage or the query cache.
  const form = useForm({
    initialValues: { number: '', expiry: '', cvv: '' },
    validate: { number: cardNumberError, expiry: (v) => expiryError(v), cvv: cvvError },
  });

  const orderLink = (
    <Anchor component={Link} to={`/account/orders/${orderId}`} fw={600}>
      Open the order
    </Anchor>
  );

  let content: ReactNode;
  if (order.isPending) {
    content = <Loading lines={6} label="Loading the order…" />;
  } else if (order.isError) {
    content =
      order.error instanceof ApiError && order.error.kind === 'not-found' ? (
        <Notice>
          <Title order={2}>Order not found</Title>
          <Anchor component={Link} to="/account/orders" fw={600}>
            Your orders
          </Anchor>
        </Notice>
      ) : (
        <ErrorState error={order.error} onRetry={() => void order.refetch()} />
      );
  } else {
    const data = order.data;
    const expires = Date.parse(data.reservationExpiresAt);
    const minutes = Math.max(0, Math.ceil((expires - now) / 60_000));
    const payBy = formatTime(data.reservationExpiresAt);

    const pay = form.onSubmit(async (values) => {
      setMessage(null);
      setPaying(true);
      try {
        const paid = await payOrder(orderId, toCardDetails(values));
        form.reset();
        queryClient.setQueryData<OrderDetails>(orderKeys.detail(orderId), (old) =>
          old ? { ...old, status: paid.status } : old,
        );
        void queryClient.invalidateQueries({ queryKey: orderKeys.all });
        // Customer Service removes the ordered products from the cart shortly after.
        void queryClient.invalidateQueries({ queryKey: cartQueryKey });
        setTimeout(() => void queryClient.invalidateQueries({ queryKey: cartQueryKey }), 3_000);
        notifications.show({ message: 'Payment received' });
        navigate(`/account/orders/${orderId}`);
      } catch (error) {
        const problem = orderProblem(error);
        if (problem?.kind === 'payment-declined') {
          const reason =
            problem.reason === 'insufficient_funds' ? 'insufficient funds' : 'declined';
          setMessage(
            `This card was declined: ${reason}. Try another card; your order stays reserved until ${payBy}.`,
          );
        } else if (problem?.kind === 'payment-window-expired') {
          setWindowClosed(true);
        } else if (problem?.kind === 'payment-in-progress') {
          setMessage(
            'Your previous payment is still being processed. Try again with the same card.',
          );
        } else if (problem?.kind === 'order-state') {
          setMessage('This order is no longer awaiting payment.');
          void order.refetch();
        } else if (
          error instanceof ApiError &&
          (error.kind === 'unavailable' || error.kind === 'network')
        ) {
          setMessage(
            'The payment could not be confirmed. Trying again with the same card will not charge it twice.',
          );
        } else {
          setMessage(userMessage(error));
        }
      } finally {
        setPaying(false);
      }
    });

    let payment: ReactNode;
    if (data.status !== 'PENDING_PAYMENT') {
      payment = (
        <Notice>
          <Text fw={600}>
            {PAID.has(data.status)
              ? 'This order is paid.'
              : `This order is ${ORDER_STATUS[data.status].label.toLowerCase()}.`}
          </Text>
          {orderLink}
        </Notice>
      );
    } else if (windowClosed || now >= expires) {
      payment = (
        <Notice>
          <Text fw={600}>The payment window has passed</Text>
          <Text c="dimmed">The reserved products were released.</Text>
          {orderLink}
        </Notice>
      );
    } else {
      payment = (
        <Box component="section" aria-label="Payment" p="lg" style={card}>
          <form onSubmit={pay} noValidate>
            <Stack gap="md">
              <Title order={2}>Card payment</Title>
              {message && (
                <Alert
                  role="alert"
                  variant="light"
                  color="red"
                  styles={{
                    root: { background: 'var(--vs-danger-soft)' },
                    message: { color: 'var(--vs-danger)' },
                  }}
                >
                  {message}
                </Alert>
              )}
              <TextInput
                label="Card number"
                inputMode="numeric"
                autoComplete="cc-number"
                placeholder="1234 5678 9012 3456"
                {...form.getInputProps('number')}
              />
              <Group grow align="flex-start">
                <TextInput
                  label="Expiry (MM/YY)"
                  autoComplete="cc-exp"
                  placeholder="08/28"
                  {...form.getInputProps('expiry')}
                />
                <TextInput
                  label="CVV"
                  inputMode="numeric"
                  autoComplete="cc-csc"
                  placeholder="123"
                  {...form.getInputProps('cvv')}
                />
              </Group>
              <Stack
                gap={4}
                p="sm"
                aria-label="Test cards"
                role="group"
                style={{ background: 'var(--vs-surface-sunken)', borderRadius: 6 }}
              >
                <Text fz={13} fw={600}>
                  Test cards (demo payment provider)
                </Text>
                {TEST_CARDS.map(([number, outcome]) => (
                  <Group key={number} gap="sm" fz={13}>
                    <span className="vs-mono">{number}</span>
                    <Text fz={13} c="dimmed">
                      {outcome}
                    </Text>
                  </Group>
                ))}
              </Stack>
              <Button type="submit" size="md" loading={paying}>
                Pay {formatPrice(data.totalAmount)}
              </Button>
              <Text fz={13} c="dimmed">
                Card details go to the payment provider and are never stored by VoltStore.
              </Text>
            </Stack>
          </form>
        </Box>
      );
    }

    content = (
      <Stack gap="lg">
        {data.status === 'PENDING_PAYMENT' && !windowClosed && now < expires && (
          <Text>
            Order <ShortId id={data.orderId} /> is placed and the items are reserved for you. Pay by{' '}
            <strong>{payBy}</strong> (about {minutes} minute{minutes === 1 ? '' : 's'} left).
          </Text>
        )}
        <Group align="flex-start" gap="lg" wrap="wrap">
          <Stack gap="lg" style={{ flex: '999 1 480px', minWidth: 0 }}>
            <Stack gap="xs" p="lg" style={card} component="section" aria-label="Delivery details">
              <Group justify="space-between">
                <Title order={2}>Delivery details</Title>
                <Text fz={13} c="dimmed">
                  Saved with the order
                </Text>
              </Group>
              {[
                ['Recipient', data.deliveryDetails.name],
                ['Phone', data.deliveryDetails.phone],
                [
                  'Carrier',
                  CARRIERS.find((c) => c.value === data.deliveryDetails.carrier)?.label ??
                    data.deliveryDetails.carrier,
                ],
                ['Address', data.deliveryDetails.address],
              ].map(([label, value]) => (
                <Group key={label} gap="sm" align="flex-start" wrap="nowrap">
                  <Text fz={14} c="dimmed" w={90} style={{ flex: 'none' }}>
                    {label}
                  </Text>
                  <Text fz={14}>{value}</Text>
                </Group>
              ))}
            </Stack>
            {payment}
          </Stack>
          <Summary order={data} />
        </Group>
      </Stack>
    );
  }

  return (
    <PageContainer>
      <Stack gap="lg">
        <Title order={1}>Checkout</Title>
        <StepIndicator step={2} />
        {content}
      </Stack>
    </PageContainer>
  );
}
