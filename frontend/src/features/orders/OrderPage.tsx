// `/account/orders/:orderId` (spec customer-orders, Order details; design D8–D9).
import { Anchor, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { Link, useParams } from 'react-router';
import { ApiError } from '../../api/errors';
import { PageContainer } from '../../app/PageContainer';
import { formatDateTime, formatTime, ShortId } from '../../components/DateTime';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { formatPrice } from '../../components/Price';
import { lineKopecks } from '../cart/subtotal';
import { CARRIERS } from '../checkout/CheckoutPage';
import { CancelOrder } from './CancelOrder';
import { useOrder } from './queries';
import { DeliveryStatusChip, OrderStatusChip } from './status';

const TABULAR = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const;

const block = {
  background: 'var(--vs-surface-raised)',
  border: '1px solid var(--vs-line)',
  borderRadius: 10,
} as const;

export function OrderPage() {
  const { orderId = '' } = useParams();
  const order = useOrder(orderId);

  if (order.isPending) {
    return (
      <PageContainer>
        <Loading lines={6} label="Loading the order…" />
      </PageContainer>
    );
  }
  if (order.isError) {
    return (
      <PageContainer>
        {order.error instanceof ApiError && order.error.kind === 'not-found' ? (
          <Stack align="center" gap="sm" py={48} ta="center">
            <Title order={1}>Order not found</Title>
            <Anchor component={Link} to="/account/orders" fw={600}>
              Your orders
            </Anchor>
          </Stack>
        ) : (
          <ErrorState error={order.error} onRetry={() => void order.refetch()} />
        )}
      </PageContainer>
    );
  }

  const data = order.data;
  const awaitingPayment = data.status === 'PENDING_PAYMENT';
  const tracked = data.ttn != null || data.deliveryStatus != null;

  return (
    <PageContainer>
      <Stack gap="lg">
        <Anchor component={Link} to="/account/orders" fz="sm">
          ← Your orders
        </Anchor>
        <Group gap="md" wrap="wrap" align="center">
          <Title order={1}>
            Order <ShortId id={data.orderId} />
          </Title>
          <OrderStatusChip status={data.status} />
        </Group>
        <Group gap="xl" wrap="wrap">
          <Text fz={14} c="dimmed">
            Placed {formatDateTime(data.placedAt)}
          </Text>
          {data.paidAt != null && (
            <Text fz={14} c="dimmed">
              Paid {formatDateTime(data.paidAt)}
            </Text>
          )}
        </Group>
        {data.status === 'CANCELLATION_REQUESTED' && (
          <Text>
            The cancellation is being completed; the refund and the stock release finish shortly.
          </Text>
        )}

        {awaitingPayment && (
          <Group gap="md" p="md" style={{ ...block, background: 'var(--vs-warning-soft)' }}>
            <Text>
              Pay by <strong>{formatTime(data.reservationExpiresAt)}</strong> to keep the products
              reserved.
            </Text>
            <Button component={Link} to={`/checkout/${data.orderId}`}>
              Pay now
            </Button>
          </Group>
        )}

        <Stack gap="sm" p="lg" style={block} component="section" aria-label="Items">
          <Title order={2}>Items</Title>
          <Table.ScrollContainer minWidth={420}>
            <Table verticalSpacing="sm">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Product</Table.Th>
                  <Table.Th ta="right">Price</Table.Th>
                  <Table.Th ta="right">Quantity</Table.Th>
                  <Table.Th ta="right">Total</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {data.items.map((item) => (
                  <Table.Tr key={item.productId}>
                    <Table.Td>{item.productTitle}</Table.Td>
                    <Table.Td ta="right" style={TABULAR}>
                      {formatPrice(item.unitPriceAtOrder)}
                    </Table.Td>
                    <Table.Td ta="right">{item.quantity}</Table.Td>
                    <Table.Td ta="right" style={TABULAR}>
                      {formatPrice(lineKopecks(item.unitPriceAtOrder, item.quantity) / 100)}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          <Group justify="space-between">
            <Text fw={600}>Total</Text>
            <Text fw={700} fz={20} className="vs-price" data-testid="order-total">
              {formatPrice(data.totalAmount)}
            </Text>
          </Group>
        </Stack>

        <Stack gap="xs" p="lg" style={block} component="section" aria-label="Delivery">
          <Title order={2}>Delivery</Title>
          <Text fz={14}>
            {data.deliveryDetails.name}, {data.deliveryDetails.phone}
          </Text>
          <Text fz={14}>
            {CARRIERS.find((c) => c.value === data.deliveryDetails.carrier)?.label ??
              data.deliveryDetails.carrier}
            : {data.deliveryDetails.address}
          </Text>
          {tracked ? (
            <Group gap="md" pt="xs">
              {data.ttn != null && (
                <Text fz={14}>
                  TTN <span className="vs-mono">{data.ttn}</span>
                </Text>
              )}
              {data.deliveryStatus != null && <DeliveryStatusChip status={data.deliveryStatus} />}
            </Group>
          ) : (
            !awaitingPayment &&
            data.status !== 'CANCELLED' &&
            data.status !== 'EXPIRED' && (
              <Text fz={14} c="dimmed">
                Tracking appears once the parcel is prepared.
              </Text>
            )
          )}
        </Stack>

        <CancelOrder order={data} />
      </Stack>
    </PageContainer>
  );
}
