// `/account/orders` (spec customer-orders, Order history; design D8).
import { Anchor, Box, Group, Stack, Text, Title } from '@mantine/core';
import { Link, useSearchParams } from 'react-router';
import { PageContainer } from '../../app/PageContainer';
import { formatDateTime, ShortId } from '../../components/DateTime';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { formatPrice } from '../../components/Price';
import { Paging } from '../catalog/Paging';
import { useOrders } from './queries';
import { OrderStatusChip } from './status';

export function OrderHistoryPage() {
  const [params, setParams] = useSearchParams();
  // The URL page is 1-based, Order's `page` zero-based.
  const page = Math.max(1, Number(params.get('page')) || 1);
  const orders = useOrders(page - 1);

  let body;
  if (orders.isPending) body = <Loading lines={5} label="Loading your orders…" />;
  else if (orders.isError) {
    body = <ErrorState error={orders.error} onRetry={() => void orders.refetch()} />;
  } else if (orders.data.totalElements === 0) {
    body = (
      <Stack align="center" gap="sm" py={48} ta="center">
        <Title order={2}>You have no orders yet</Title>
        <Anchor component={Link} to="/" fw={600}>
          Browse the catalog
        </Anchor>
      </Stack>
    );
  } else {
    const { content, size, totalElements } = orders.data;
    body = (
      <Stack gap="lg">
        <Box
          component="ul"
          aria-label="Orders"
          p={0}
          m={0}
          style={{
            listStyle: 'none',
            background: 'var(--vs-surface-raised)',
            border: '1px solid var(--vs-line)',
            borderRadius: 10,
            overflow: 'hidden',
          }}
        >
          {content.map((order, index) => (
            <Box
              component="li"
              key={order.orderId}
              data-testid="order-row"
              style={{ borderTop: index === 0 ? undefined : '1px solid var(--vs-line)' }}
            >
              <Anchor
                component={Link}
                to={`/account/orders/${order.orderId}`}
                underline="never"
                c="var(--vs-ink)"
                display="block"
                p="md"
              >
                <Group justify="space-between" gap="md" wrap="wrap">
                  <Group gap="md" wrap="wrap">
                    <Text fw={600}>
                      <ShortId id={order.orderId} />
                    </Text>
                    <Text c="dimmed" fz={14}>
                      {formatDateTime(order.placedAt)}
                    </Text>
                  </Group>
                  <Group gap="md" wrap="nowrap">
                    <Text fw={700} className="vs-price">
                      {formatPrice(order.totalAmount)}
                    </Text>
                    <OrderStatusChip status={order.status} />
                  </Group>
                </Group>
              </Anchor>
            </Box>
          ))}
        </Box>
        <Paging
          page={page}
          totalPages={Math.ceil(totalElements / size)}
          onPage={(next) => setParams(next > 1 ? { page: String(next) } : {})}
        />
      </Stack>
    );
  }

  return (
    <PageContainer>
      <Stack gap="lg">
        <Title order={1}>Orders</Title>
        {body}
      </Stack>
    </PageContainer>
  );
}
