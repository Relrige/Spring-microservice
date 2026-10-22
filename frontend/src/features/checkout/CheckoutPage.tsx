// `/checkout`: the delivery step (spec checkout; change customer-checkout-orders, design D5–D6).
import {
  Alert,
  Anchor,
  Box,
  Button,
  Checkbox,
  Group,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { saveProfile } from '../../api/customer';
import { userMessage } from '../../api/errors';
import { orderProblem, placeOrder, type PlaceOrderRequest } from '../../api/order';
import { PageContainer } from '../../app/PageContainer';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { formatPrice } from '../../components/Price';
import { useCart } from '../cart/CartProvider';
import { availableLines, useLineViews } from '../cart/line-views';
import { estimatedSubtotal, lineKopecks } from '../cart/subtotal';
import {
  isEmptyProfile,
  normalizePhone,
  phoneError,
  profileKey,
  useProfile,
} from '../profile/profile';
import { attemptFor, splitRecipient, type Attempt } from './attempt';

// BL-11: carriers hardcoded until Order lists them.
const CARRIERS = [{ value: 'NOVA_POSHTA', label: 'Nova Poshta' }];

const required = (message: string) => (value: string) => (value.trim() ? null : message);
const wait = (seconds: number) => new Promise((resolve) => setTimeout(resolve, seconds * 1000));

export function StepIndicator({ step }: { step: 1 | 2 }) {
  return (
    <Group
      component="ol"
      aria-label="Checkout steps"
      gap="lg"
      p={0}
      m={0}
      style={{ listStyle: 'none' }}
    >
      <Text component="li" fw={600} c={step === 1 ? 'var(--vs-primary)' : 'dimmed'}>
        1. Delivery details{step === 2 ? ' · done' : ''}
      </Text>
      <Text component="li" fw={600} c={step === 2 ? 'var(--vs-primary)' : 'dimmed'}>
        2. Payment
      </Text>
    </Group>
  );
}

export function CheckoutPage() {
  const cart = useCart();
  const views = useLineViews(cart.lines);
  const profile = useProfile();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [attempt, setAttempt] = useState<Attempt | null>(null);
  const [problem, setProblem] = useState<ReactNode>(null);
  const [submitting, setSubmitting] = useState(false);
  const form = useForm({
    initialValues: {
      recipient: '',
      phone: '',
      address: '',
      carrier: 'NOVA_POSHTA',
      saveToProfile: false,
    },
    validate: {
      recipient: required('Enter the recipient'),
      phone: phoneError,
      address: required('Enter the delivery address or branch'),
      carrier: required('Choose a carrier'),
    },
  });

  // Spec customer-profile, Profile pre-fills delivery details: once, and only untouched fields.
  const prefilled = useRef(false);
  const { data: saved } = profile;
  const { isTouched, setFieldValue } = form;
  useEffect(() => {
    if (prefilled.current || profile.isPending) return;
    prefilled.current = true;
    const values = {
      recipient: [saved?.firstName, saved?.lastName].filter(Boolean).join(' '),
      phone: saved?.phone ?? '',
      address: saved?.defaultAddress ?? '',
    };
    for (const [field, value] of Object.entries(values)) {
      if (value && !isTouched(field)) setFieldValue(field as keyof typeof values, value);
    }
    setFieldValue('saveToProfile', isEmptyProfile(saved));
  }, [saved, profile.isPending, isTouched, setFieldValue]);

  const titleOf = (productId: string) => {
    const view = views[cart.lines.findIndex((line) => line.productId === productId)];
    return view && 'product' in view ? view.product.title : 'A product';
  };

  const submit = form.onSubmit(async (values) => {
    setProblem(null);
    setSubmitting(true);
    const body: PlaceOrderRequest = {
      items: cart.lines.map(({ productId, quantity }) => ({ productId, quantity })),
      deliveryDetails: {
        name: values.recipient.trim(),
        phone: normalizePhone(values.phone),
        address: values.address.trim(),
        carrier: values.carrier,
      },
    };
    const next = attemptFor(attempt, JSON.stringify(body));
    setAttempt(next);
    const { key } = next;
    try {
      let placed;
      try {
        placed = await placeOrder(body, key);
      } catch (error) {
        // A submission with this key is still running: ask once more after Retry-After.
        const first = orderProblem(error);
        if (first?.kind !== 'request-in-progress') throw error;
        await wait(first.retryAfter ?? 1);
        placed = await placeOrder(body, key);
      }
      if (values.saveToProfile) {
        saveProfile({
          ...splitRecipient(body.deliveryDetails.name),
          phone: body.deliveryDetails.phone,
          defaultAddress: body.deliveryDetails.address,
        })
          .then((profileSaved) => queryClient.setQueryData(profileKey, profileSaved))
          // Spec checkout, Profile save fails: the checkout goes on.
          .catch((error: unknown) => console.warn('[VoltStore] Profile not saved', error));
      }
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
      navigate(`/checkout/${placed.orderId}`, { replace: true });
    } catch (error) {
      setProblem(describe(error));
    } finally {
      setSubmitting(false);
    }
  });

  function describe(error: unknown): ReactNode {
    const found = orderProblem(error);
    switch (found?.kind) {
      case 'ineligible-items':
        return (
          <>
            {found.items
              .map(({ productId }) => `${titleOf(productId)} is no longer available.`)
              .join(' ')}{' '}
            <Anchor component={Link} to="/cart" fw={600}>
              Back to the cart
            </Anchor>
          </>
        );
      case 'insufficient-stock':
        return found.items
          .map(
            ({ productId, requested, available }) =>
              `Not enough stock for ${titleOf(productId)}: requested ${requested}, available ${available}.`,
          )
          .join('\n');
      case 'order-total-too-large':
        return `The order exceeds the maximum total of ${formatPrice(found.maxTotal)}.`;
      case 'idempotency-key-mismatch':
        setAttempt(null);
        return 'Your order details changed while it was being placed. Place the order again.';
      case 'request-in-progress':
        return userMessage(null);
      default:
        return userMessage(error);
    }
  }

  let body: ReactNode;
  if (cart.status === 'loading') body = <Loading lines={5} label="Loading your cart…" />;
  else if (cart.status === 'error') body = <ErrorState error={cart.error} onRetry={cart.reload} />;
  else if (cart.lines.length === 0) {
    body = (
      <Stack align="center" gap="sm" py={48} ta="center">
        <Title order={2}>Your cart is empty</Title>
        <Anchor component={Link} to="/" fw={600}>
          Browse the catalog
        </Anchor>
      </Stack>
    );
  } else {
    const available = availableLines(cart.lines, views);
    body = (
      <Group align="flex-start" gap="lg" wrap="wrap">
        <Box
          component="section"
          aria-label="Delivery details"
          p="lg"
          style={{
            flex: '999 1 480px',
            minWidth: 0,
            background: 'var(--vs-surface-raised)',
            border: '1px solid var(--vs-line)',
            borderRadius: 10,
          }}
        >
          <form onSubmit={submit} noValidate>
            <Stack gap="md">
              <Title order={2}>Delivery details</Title>
              {problem && (
                <Alert
                  role="alert"
                  variant="light"
                  color="red"
                  styles={{
                    root: { background: 'var(--vs-danger-soft)' },
                    message: { color: 'var(--vs-danger)', whiteSpace: 'pre-line' },
                  }}
                >
                  {problem}
                </Alert>
              )}
              <TextInput
                label="Recipient"
                autoComplete="name"
                {...form.getInputProps('recipient')}
              />
              <TextInput
                label="Phone"
                type="tel"
                autoComplete="tel"
                placeholder="+380 50 123 45 67"
                {...form.getInputProps('phone')}
              />
              <Textarea
                label="Address"
                description="City and carrier branch, or a street address."
                autosize
                minRows={2}
                {...form.getInputProps('address')}
              />
              <Select
                label="Carrier"
                data={CARRIERS}
                allowDeselect={false}
                {...form.getInputProps('carrier')}
              />
              <Checkbox
                label="Save these details to my profile"
                {...form.getInputProps('saveToProfile', { type: 'checkbox' })}
              />
              <Group>
                <Button type="submit" size="md" loading={submitting}>
                  Place order
                </Button>
              </Group>
              <Text fz={13} c="dimmed">
                Placing the order reserves the products for 15 minutes while you pay.
              </Text>
            </Stack>
          </form>
        </Box>
        <Stack
          component="aside"
          aria-label="Order summary"
          gap="sm"
          p="lg"
          style={{
            flex: '1 1 320px',
            maxWidth: '100%',
            background: 'var(--vs-surface-raised)',
            border: '1px solid var(--vs-line)',
            borderRadius: 16,
          }}
        >
          <Title order={2}>Your order</Title>
          {cart.lines.map((line, index) => {
            const view = views[index]!;
            return (
              <Group key={line.productId} justify="space-between" gap="sm" wrap="nowrap">
                <Text fz={14}>
                  {'product' in view ? view.product.title : 'Loading…'} × {line.quantity}
                </Text>
                {view.kind === 'available' && (
                  <Text fz={14} className="vs-price">
                    {formatPrice(lineKopecks(view.product.basePrice, line.quantity) / 100)}
                  </Text>
                )}
              </Group>
            );
          })}
          <Group
            justify="space-between"
            gap="sm"
            pt="sm"
            style={{ borderTop: '1px solid var(--vs-line)' }}
          >
            <Text fw={600}>Estimated total</Text>
            <Text fw={700} className="vs-price" data-testid="checkout-total">
              {formatPrice(estimatedSubtotal(available))}
            </Text>
          </Group>
          <Text fz={13} c="dimmed">
            The final prices are fixed when the order is placed.
          </Text>
        </Stack>
      </Group>
    );
  }

  return (
    <PageContainer>
      <Stack gap="lg">
        <Title order={1}>Checkout</Title>
        <StepIndicator step={1} />
        {body}
      </Stack>
    </PageContainer>
  );
}
