// `/manage/products/:productId` (spec product-management; design D6, D8).
import { Alert, Anchor, Box, Button, Group, Stack, Text, Title } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconAlertCircle } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { updateProduct, type ProductDetails } from '../../api/catalog';
import { ApiError } from '../../api/errors';
import { PageContainer } from '../../app/PageContainer';
import { ShortId } from '../../components/DateTime';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { StockBadge } from '../../components/StatusChip';
import { OVERLAP_MESSAGE } from './messages';
import { ProductForm } from './ProductForm';
import {
  ProductStatusChip,
  ProductStatusControl,
  type StatusFailure,
} from './ProductStatusControl';
import { invalidateCatalog, manageKeys, useManagedProduct } from './queries';

export const CREATED_NOTICE = 'Product created. It stays inactive until you activate it.';

const valuesOf = (product: ProductDetails) => ({
  title: product.title,
  description: product.description,
  categoryId: product.categoryId,
  basePrice: product.basePrice,
  imageUrl: product.imageUrl ?? '',
});

export function ProductEditPage() {
  const productId = useParams().productId!;
  const product = useManagedProduct(productId);
  const queryClient = useQueryClient();
  const twoColumns = useMediaQuery('(min-width: 900px)', true, { getInitialValueInEffect: false });
  // The form is filled once from the first data and again only on "Reload product".
  const [formVersion, setFormVersion] = useState(0);
  const [saveConflict, setSaveConflict] = useState(false);
  const [statusFailure, setStatusFailure] = useState<StatusFailure | null>(null);
  const [reloading, setReloading] = useState(false);
  useCreatedNotice();

  const reload = async () => {
    setReloading(true);
    try {
      await product.refetch();
      setFormVersion((version) => version + 1);
      setSaveConflict(false);
      setStatusFailure(null);
    } finally {
      setReloading(false);
    }
  };

  let body: ReactNode;
  if (product.isPending) body = <Loading lines={6} label="Loading the product…" />;
  else if (product.isError) {
    body =
      product.error instanceof ApiError && product.error.kind === 'not-found' ? (
        <Stack align="flex-start" gap="sm" py={24}>
          <Title order={2}>This product does not exist</Title>
          <Anchor component={Link} to="/manage/products" fw={600}>
            Back to the product list
          </Anchor>
        </Stack>
      ) : (
        <ErrorState error={product.error} onRetry={() => void product.refetch()} />
      );
  } else {
    const data = product.data;
    body = (
      <Box
        style={{
          display: 'grid',
          gridTemplateColumns: twoColumns ? 'minmax(0, 1fr) 320px' : 'minmax(0, 1fr)',
          gap: 24,
          alignItems: 'start',
        }}
      >
        <Stack gap="md" style={{ minWidth: 0, order: twoColumns ? 0 : 1 }}>
          {saveConflict && (
            <ConflictAlert onReload={() => void reload()} reloading={reloading}>
              {OVERLAP_MESSAGE}
            </ConflictAlert>
          )}
          <ProductForm
            key={formVersion}
            initialValues={valuesOf(data)}
            submitLabel="Save changes"
            onConflict={() => setSaveConflict(true)}
            onSubmit={async (input) => {
              setSaveConflict(false);
              const saved = await updateProduct(productId, input);
              queryClient.setQueryData(manageKeys.product(productId), saved);
              void invalidateCatalog(queryClient);
              notifications.show({ message: 'Changes saved' });
              return valuesOf(saved);
            }}
          />
        </Stack>
        <Stack
          component="aside"
          aria-label="Product status"
          gap="md"
          p={20}
          className="vs-sunken"
          style={{ borderRadius: 10 }}
        >
          <Group gap="xs">
            <ProductStatusChip status={data.status} />
            <StockBadge status={data.stockStatus} />
          </Group>
          <Text size="sm" c="dimmed">
            Product <ShortId id={data.productId} />
          </Text>
          <Text size="sm">
            {data.status === 'ACTIVE'
              ? 'Customers can find and buy this product.'
              : 'Customers do not see this product until you activate it.'}
          </Text>
          {statusFailure &&
            (statusFailure.conflict ? (
              <ConflictAlert onReload={() => void reload()} reloading={reloading}>
                {statusFailure.message}
              </ConflictAlert>
            ) : (
              <DangerAlert>{statusFailure.message}</DangerAlert>
            ))}
          <Group>
            <ProductStatusControl product={data} onFailure={setStatusFailure} />
          </Group>
        </Stack>
      </Box>
    );
  }

  return (
    <PageContainer>
      <Stack gap="lg">
        <Anchor component={Link} to="/manage/products" size="sm">
          ← Products
        </Anchor>
        <Stack gap={4}>
          <Title order={1}>Edit product</Title>
          {product.data && <Text c="dimmed">{product.data.title}</Text>}
        </Stack>
        {body}
      </Stack>
    </PageContainer>
  );
}

/** The one-time notice after a create: shown from the router state, which is then cleared. */
function useCreatedNotice() {
  const location = useLocation();
  const navigate = useNavigate();
  const shown = useRef(false);
  const created = (location.state as { created?: boolean } | null)?.created === true;
  useEffect(() => {
    if (!created || shown.current) return;
    shown.current = true;
    notifications.show({ message: CREATED_NOTICE });
    navigate(location.pathname, { replace: true, state: null });
  }, [created, location.pathname, navigate]);
}

function DangerAlert({ children }: { children: ReactNode }) {
  return (
    <Alert
      role="alert"
      variant="light"
      color="red"
      radius="md"
      icon={<IconAlertCircle size={20} stroke={1.75} />}
      styles={{
        root: { background: 'var(--vs-danger-soft)' },
        message: { color: 'var(--vs-danger)' },
        icon: { color: 'var(--vs-danger)' },
      }}
    >
      {children}
    </Alert>
  );
}

function ConflictAlert({
  children,
  onReload,
  reloading,
}: {
  children: ReactNode;
  onReload: () => void;
  reloading: boolean;
}) {
  return (
    <DangerAlert>
      <Stack gap="sm" align="flex-start">
        <Text inherit>{children}</Text>
        <Button variant="outline" color="red" size="sm" loading={reloading} onClick={onReload}>
          Reload product
        </Button>
      </Stack>
    </DangerAlert>
  );
}
