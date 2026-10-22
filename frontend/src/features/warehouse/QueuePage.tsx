// `/warehouse/queue` (spec fulfillment-queue; design D7).
import { Alert, Anchor, Button, Group, Modal, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { retryTtn, ttnFailure } from '../../api/delivery';
import { ApiError, userMessage } from '../../api/errors';
import { shipOrder, type FulfillmentQueue, type QueueEntry } from '../../api/order';
import { PageContainer } from '../../app/PageContainer';
import { formatDateTime, ShortId } from '../../components/DateTime';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { CARRIERS } from '../checkout/CheckoutPage';
import { DeliveryStatusChip } from '../orders/status';
import { queueKey, useQueue } from './queries';

const card = {
  background: 'var(--vs-surface-raised)',
  border: '1px solid var(--vs-line)',
  borderRadius: 10,
} as const;

/** `packaging_data_missing:{productId}` names the product and links to its stock row. */
function failureText(order: QueueEntry, reason: string): ReactNode {
  const missing = /^packaging_data_missing:(.+)$/.exec(reason);
  const item = missing && order.items.find((i) => i.productId === missing[1]);
  if (!item) return reason;
  return (
    <>
      {item.productTitle} has no packaging data.{' '}
      <Anchor
        component={Link}
        to={`/warehouse?q=${encodeURIComponent(item.productTitle)}`}
        fw={600}
      >
        Set packaging
      </Anchor>
    </>
  );
}

function QueueCard({ order, actions }: { order: QueueEntry; actions: boolean }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<ReactNode>(null);
  const update = (change: (entry: QueueEntry) => QueueEntry | null) =>
    queryClient.setQueryData<FulfillmentQueue>(
      queueKey,
      (old) =>
        old && {
          ...old,
          orders: old.orders.flatMap((entry) => {
            if (entry.orderId !== order.orderId) return [entry];
            const next = change(entry);
            return next ? [next] : [];
          }),
        },
    );
  const reload = () => void queryClient.invalidateQueries({ queryKey: queueKey });

  const ship = async () => {
    setConfirming(false);
    setMessage(null);
    setBusy(true);
    try {
      await shipOrder(order.orderId);
      notifications.show({ message: 'Order shipped' });
      update(() => null);
      reload();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setMessage('This order changed; the queue was reloaded.');
        reload();
      } else setMessage(userMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const retry = async () => {
    setMessage(null);
    setBusy(true);
    try {
      const { ttn } = await retryTtn(order.orderId);
      update((entry) => ({ ...entry, ttn, deliveryStatus: 'TTN_GENERATED', failureReason: null }));
      reload();
    } catch (error) {
      const reason = ttnFailure(error);
      if (reason) setMessage(failureText(order, reason));
      else if (error instanceof ApiError && error.status === 409) reload();
      else setMessage(userMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const { deliveryDetails: to } = order;
  return (
    <Stack component="li" gap="sm" p="lg" style={card} data-testid="queue-order">
      <Group justify="space-between" wrap="wrap">
        <Group gap="md">
          <Text fw={600}>
            <ShortId id={order.orderId} />
          </Text>
          <Text fz={14} c="dimmed">
            Paid {formatDateTime(order.paidAt)}
          </Text>
        </Group>
        {order.deliveryStatus != null && <DeliveryStatusChip status={order.deliveryStatus} />}
      </Group>
      <Stack gap={2}>
        {order.items.map((item) => (
          <Text key={item.productId} fz={14}>
            {item.productTitle} × {item.quantity}
          </Text>
        ))}
      </Stack>
      <Text fz={14} c="dimmed">
        {to.name}, {to.phone} · {CARRIERS.find((c) => c.value === to.carrier)?.label ?? to.carrier}:{' '}
        {to.address}
      </Text>
      {order.ttn != null && (
        <Text fz={14}>
          TTN <span className="vs-mono">{order.ttn}</span>
        </Text>
      )}
      {order.deliveryStatus === 'TTN_FAILED' && order.failureReason != null && (
        <Text fz={14} c="var(--vs-danger)">
          {failureText(order, order.failureReason)}
        </Text>
      )}
      {message && (
        <Alert
          role="alert"
          variant="light"
          color="red"
          styles={{ message: { color: 'var(--vs-danger)' } }}
        >
          {message}
        </Alert>
      )}
      {actions && order.deliveryStatus === 'TTN_GENERATED' && (
        <Group>
          <Button loading={busy} onClick={() => setConfirming(true)}>
            Mark as shipped
          </Button>
        </Group>
      )}
      {actions && order.deliveryStatus === 'TTN_FAILED' && (
        <Group>
          <Button variant="default" loading={busy} onClick={() => void retry()}>
            Retry TTN
          </Button>
        </Group>
      )}
      <Modal opened={confirming} onClose={() => setConfirming(false)} title="Mark as shipped?">
        <Stack gap="md">
          <Text>
            The parcel with TTN <span className="vs-mono">{order.ttn}</span> is handed to the
            carrier.
          </Text>
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button onClick={() => void ship()}>Mark as shipped</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

export function QueuePage() {
  // BL-13: polling until Order pushes status changes.
  const queue = useQueue({ refetchInterval: 5_000 });

  let body: ReactNode;
  if (queue.isPending) body = <Loading lines={5} label="Loading the queue…" />;
  else if (queue.isError)
    body = <ErrorState error={queue.error} onRetry={() => void queue.refetch()} />;
  else {
    const { orders, deliveryDataAvailable } = queue.data;
    body = (
      <Stack gap="md">
        {!deliveryDataAvailable && (
          <Alert
            variant="light"
            color="yellow"
            styles={{
              root: { background: 'var(--vs-warning-soft)' },
              message: { color: 'var(--vs-warning)' },
            }}
          >
            Delivery statuses cannot be loaded right now, so orders cannot be shipped. The list
            updates by itself.
          </Alert>
        )}
        {orders.length === 0 ? (
          <Text c="dimmed" py={48} ta="center">
            No orders are waiting for dispatch
          </Text>
        ) : (
          <Stack component="ol" gap="md" p={0} m={0} style={{ listStyle: 'none' }}>
            {orders.map((order) => (
              <QueueCard key={order.orderId} order={order} actions={deliveryDataAvailable} />
            ))}
          </Stack>
        )}
      </Stack>
    );
  }

  return (
    <PageContainer>
      <Stack gap="lg">
        <Stack gap={4}>
          <Title order={1}>Fulfillment queue</Title>
          <Text c="dimmed">Paid orders waiting for dispatch, oldest payment first.</Text>
        </Stack>
        {body}
      </Stack>
    </PageContainer>
  );
}
