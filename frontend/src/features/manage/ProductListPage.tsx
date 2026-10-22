// `/manage/products`: the staff product list (spec product-management; design D5).
import {
  Alert,
  Anchor,
  Button,
  Group,
  NativeSelect,
  SegmentedControl,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { IconAlertCircle, IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { ProductStatus, SortBy } from '../../api/catalog';
import { PageContainer } from '../../app/PageContainer';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { Price } from '../../components/Price';
import { ProductImage } from '../../components/ProductImage';
import { StockBadge } from '../../components/StatusChip';
import { Paging } from '../catalog/Paging';
import { useCategories } from '../catalog/queries';
import { SORT_OPTIONS } from '../catalog/url-state';
import { ProductStatusChip, ProductStatusControl } from './ProductStatusControl';
import { useManagedProducts } from './queries';
import { StaffSearch } from './StaffSearch';
import { StaffTable } from './StaffTable';
import {
  DEFAULT_PRODUCTS_STATE,
  parseProductsState,
  serializeProductsState,
  STATUS_OPTIONS,
  type ManagedProductsState,
} from './url-state';

const ALL = 'all';

export function ProductListPage() {
  const [params, setParams] = useSearchParams();
  const state = parseProductsState(params);
  const products = useManagedProducts(state);
  const categories = useCategories();
  const names = new Map(categories.data?.map((c) => [c.categoryId, c.name]));
  // One message above the table for the last failed status change, naming the product.
  const [failure, setFailure] = useState<{ title: string; message: string } | null>(null);

  // Every change except paging returns to the first page.
  const update = (changes: Partial<ManagedProductsState>) => {
    setFailure(null);
    const next = { ...state, ...changes, page: 'page' in changes ? changes.page! : 1 };
    setParams(serializeProductsState(next));
  };
  const clearFilters = () => {
    setFailure(null);
    setParams(serializeProductsState(DEFAULT_PRODUCTS_STATE));
  };
  const filtered = state.search !== '' || state.category !== null || state.status !== null;
  const total = products.data?.totalElements;
  const totalPages = products.data
    ? Math.max(1, Math.ceil(products.data.totalElements / products.data.size))
    : 1;

  return (
    <PageContainer>
      <Stack gap="lg">
        <Group justify="space-between" align="flex-end" gap="md">
          <Stack gap={4}>
            <Title order={1}>Products</Title>
            <Text size="sm" c="dimmed" aria-live="polite">
              {total === undefined ? ' ' : `${total} product${total === 1 ? '' : 's'}`}
            </Text>
          </Stack>
          <Button
            component={Link}
            to="/manage/products/new"
            leftSection={<IconPlus size={20} stroke={1.75} />}
          >
            New product
          </Button>
        </Group>

        <Group gap="sm" align="flex-end" wrap="wrap" role="toolbar" aria-label="Product filters">
          <StaffSearch
            key={state.search}
            initial={state.search}
            label="Search products"
            placeholder="Title or description"
            onSearch={(search) => update({ search })}
          />
          <NativeSelect
            label="Category"
            value={state.category ?? ALL}
            data={[
              { value: ALL, label: 'All categories' },
              ...(categories.data ?? []).map((c) => ({ value: c.categoryId, label: c.name })),
            ]}
            onChange={(event) => {
              const value = event.currentTarget.value;
              update({ category: value === ALL ? null : value });
            }}
          />
          <Stack gap={4}>
            <Text component="span" size="sm" fw={600} id="status-filter-label">
              Status
            </Text>
            <SegmentedControl
              aria-labelledby="status-filter-label"
              value={state.status ?? ALL}
              data={[{ value: ALL, label: 'All' }, ...STATUS_OPTIONS]}
              onChange={(value) =>
                update({ status: value === ALL ? null : (value as ProductStatus) })
              }
            />
          </Stack>
          <NativeSelect
            label="Sort by"
            value={state.sort}
            data={SORT_OPTIONS}
            onChange={(event) => update({ sort: event.currentTarget.value as SortBy })}
          />
        </Group>

        {failure && (
          <Alert
            role="alert"
            variant="light"
            color="red"
            radius="md"
            icon={<IconAlertCircle size={20} stroke={1.75} />}
            withCloseButton
            closeButtonLabel="Dismiss"
            onClose={() => setFailure(null)}
            styles={{
              root: { background: 'var(--vs-danger-soft)' },
              message: { color: 'var(--vs-danger)' },
              icon: { color: 'var(--vs-danger)' },
            }}
          >
            {failure.title}: {failure.message}
          </Alert>
        )}

        {products.isPending && <Loading lines={8} label="Loading products…" />}
        {products.isError && (
          <ErrorState error={products.error} onRetry={() => void products.refetch()} />
        )}
        {products.isSuccess && products.data.content.length === 0 && (
          <Stack align="center" gap="sm" py={48} ta="center">
            {total ? (
              <>
                <Title order={3}>This page has no products</Title>
                <Button variant="outline" onClick={() => update({ page: 1 })}>
                  Go to the first page
                </Button>
              </>
            ) : filtered ? (
              <>
                <Title order={3}>No products match your filters</Title>
                <Button variant="outline" onClick={clearFilters}>
                  Clear filters
                </Button>
              </>
            ) : (
              <Title order={3}>There are no products yet</Title>
            )}
          </Stack>
        )}
        {products.isSuccess && products.data.content.length > 0 && (
          <>
            <StaffTable
              label="Products"
              minWidth={760}
              columns={[
                { label: 'Image', hidden: true },
                { label: 'Product' },
                { label: 'Category' },
                { label: 'Price', align: 'right' },
                { label: 'Status' },
                { label: 'Stock' },
                { label: 'Actions', hidden: true },
              ]}
            >
              {products.data.content.map((product) => {
                const categoryName = names.get(product.categoryId);
                return (
                  <Table.Tr key={product.productId} data-testid="product-row">
                    <Table.Td w={72}>
                      <ProductImage categoryName={categoryName} size={40} radius={6} />
                    </Table.Td>
                    <Table.Td>
                      <Anchor
                        component={Link}
                        to={`/manage/products/${product.productId}`}
                        fw={600}
                        fz={14}
                        c="var(--vs-ink)"
                      >
                        {product.title}
                      </Anchor>
                    </Table.Td>
                    <Table.Td c="dimmed">{categoryName ?? ''}</Table.Td>
                    <Table.Td ta="right">
                      <Price value={product.basePrice} />
                    </Table.Td>
                    <Table.Td>
                      <ProductStatusChip status={product.status} />
                    </Table.Td>
                    <Table.Td>
                      <StockBadge status={product.stockStatus} />
                    </Table.Td>
                    <Table.Td ta="right" style={{ whiteSpace: 'nowrap' }}>
                      <Group gap={6} justify="flex-end" wrap="nowrap">
                        <Button
                          component={Link}
                          to={`/manage/products/${product.productId}`}
                          size="compact-sm"
                          variant="default"
                        >
                          Edit
                        </Button>
                        <ProductStatusControl
                          compact
                          product={product}
                          onFailure={(result) => {
                            setFailure(result && { title: product.title, message: result.message });
                            // A conflict on the list reads the list again (design D7).
                            if (result?.conflict) void products.refetch();
                          }}
                        />
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </StaffTable>
            <Paging page={state.page} totalPages={totalPages} onPage={(page) => update({ page })} />
          </>
        )}
      </Stack>
    </PageContainer>
  );
}
