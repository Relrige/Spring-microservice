// `/warehouse` (spec warehouse-stock; mockup WarehouseStock; design D4).
import {
  Button,
  Group,
  SegmentedControl,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { listStock, type StockItem, type StockQuery } from '../../api/inventory';
import { PageContainer } from '../../app/PageContainer';
import { ShortId } from '../../components/DateTime';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { StatusChip } from '../../components/StatusChip';
import { Paging } from '../catalog/Paging';
import { AdjustDialog, PackagingDialog, stockKey } from './StockDialogs';

const PAGE_SIZE = 20;
const SORTS: Record<string, string> = {
  title: 'title,asc',
  available: 'availableQuantity,asc',
  reserved: 'reservedQuantity,desc',
};
const NUMBER = { fontVariantNumeric: 'tabular-nums', textAlign: 'right' } as const;

export function StockPage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const packaging = params.get('packaging') ?? '';
  const status = params.get('status') ?? '';
  const sort = params.get('sort') ?? '';
  const page = Math.max(1, Number(params.get('page')) || 1);
  const [search, setSearch] = useState(q);
  const [adjusting, setAdjusting] = useState<StockItem | null>(null);
  const [packing, setPacking] = useState<StockItem | null>(null);

  const query: StockQuery = {
    search: q || undefined,
    packagingConfigured: packaging === 'set' ? true : packaging === 'missing' ? false : undefined,
    productStatus: status === 'ACTIVE' || status === 'NOT_ACTIVE' ? status : undefined,
    sortBy: SORTS[sort],
    page: page - 1,
    size: PAGE_SIZE,
  };
  const stock = useQuery({ queryKey: [...stockKey, query], queryFn: () => listStock(query) });

  /** Changes the URL state; any change but paging starts again at page 1. */
  const update = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    if (!('page' in changes)) next.delete('page');
    for (const [key, value] of Object.entries(changes)) {
      if (value && !(key === 'page' && value === '1')) next.set(key, value);
      else next.delete(key);
    }
    setParams(next);
  };

  let body;
  if (stock.isPending) body = <Loading lines={6} label="Loading stock…" />;
  else if (stock.isError) {
    body = <ErrorState error={stock.error} onRetry={() => void stock.refetch()} />;
  } else if (stock.data.totalElements === 0) {
    body = (
      <Stack align="center" gap="sm" py={48} ta="center">
        <Title order={2}>No stock items match these filters</Title>
        <Button variant="default" onClick={() => setParams({})}>
          Clear filters
        </Button>
      </Stack>
    );
  } else {
    body = (
      <Stack gap="lg">
        <Table.ScrollContainer minWidth={820}>
          <Table
            verticalSpacing="sm"
            style={{
              background: 'var(--vs-surface-raised)',
              border: '1px solid var(--vs-line)',
              borderRadius: 10,
            }}
          >
            <Table.Thead style={{ background: 'var(--vs-surface-sunken)' }}>
              <Table.Tr>
                <Table.Th className="vs-label">Product</Table.Th>
                <Table.Th className="vs-label">Catalog</Table.Th>
                <Table.Th className="vs-label" style={NUMBER}>
                  Available
                </Table.Th>
                <Table.Th className="vs-label" style={NUMBER}>
                  Reserved
                </Table.Th>
                <Table.Th className="vs-label" style={NUMBER}>
                  Total
                </Table.Th>
                <Table.Th className="vs-label">Packaging</Table.Th>
                <Table.Th className="vs-label">Actions</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {stock.data.content.map((item) => (
                <Table.Tr key={item.productId} data-testid="stock-row">
                  <Table.Td>
                    <Text fw={600}>{item.title ?? 'Title not available yet'}</Text>
                    <ShortId id={item.productId} />
                  </Table.Td>
                  <Table.Td>
                    {item.productStatus === 'ACTIVE' && (
                      <StatusChip tone="success">Active</StatusChip>
                    )}
                    {item.productStatus === 'NOT_ACTIVE' && (
                      <StatusChip tone="warning">Inactive</StatusChip>
                    )}
                  </Table.Td>
                  <Table.Td style={NUMBER} data-testid="available">
                    {item.availableQuantity}
                  </Table.Td>
                  <Table.Td style={NUMBER}>{item.reservedQuantity}</Table.Td>
                  <Table.Td style={NUMBER}>{item.totalQuantity}</Table.Td>
                  <Table.Td>
                    {item.packagingConfigured ? (
                      <Text fz={14} c="dimmed">
                        Set
                      </Text>
                    ) : (
                      <StatusChip tone="warning">Missing</StatusChip>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <Button size="xs" variant="default" onClick={() => setAdjusting(item)}>
                        Adjust
                      </Button>
                      <Button size="xs" variant="default" onClick={() => setPacking(item)}>
                        Set packaging
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Paging
          page={page}
          totalPages={Math.ceil(stock.data.totalElements / PAGE_SIZE)}
          onPage={(next) => update({ page: String(next) })}
        />
      </Stack>
    );
  }

  return (
    <PageContainer>
      <Stack gap="lg">
        <Stack gap={4}>
          <Title order={1}>Stock</Title>
          {stock.data && (
            <Text c="dimmed" data-testid="stock-count">
              {stock.data.totalElements} stock items
            </Text>
          )}
        </Stack>
        <Group gap="md" align="flex-end" wrap="wrap">
          <form
            role="search"
            onSubmit={(event) => {
              event.preventDefault();
              update({ q: search.trim() });
            }}
          >
            <Group gap="xs" align="flex-end" wrap="nowrap">
              <TextInput
                label="Search by title"
                value={search}
                onChange={(event) => setSearch(event.currentTarget.value)}
              />
              <Button type="submit" variant="default">
                Search
              </Button>
            </Group>
          </form>
          <SegmentedControl
            aria-label="Packaging filter"
            value={packaging}
            onChange={(value) => update({ packaging: value })}
            data={[
              { label: 'All', value: '' },
              { label: 'Packaging set', value: 'set' },
              { label: 'Packaging missing', value: 'missing' },
            ]}
          />
          <Select
            label="Catalog status"
            w={150}
            allowDeselect={false}
            value={status}
            onChange={(value) => update({ status: value ?? '' })}
            data={[
              { label: 'All', value: '' },
              { label: 'Active', value: 'ACTIVE' },
              { label: 'Inactive', value: 'NOT_ACTIVE' },
            ]}
          />
          <Select
            label="Sort"
            w={220}
            allowDeselect={false}
            value={sort}
            onChange={(value) => update({ sort: value ?? '' })}
            data={[
              { label: 'Product id', value: '' },
              { label: 'Title A–Z', value: 'title' },
              { label: 'Available, low to high', value: 'available' },
              { label: 'Reserved, high to low', value: 'reserved' },
            ]}
          />
        </Group>
        {body}
      </Stack>
      <AdjustDialog item={adjusting} onClose={() => setAdjusting(null)} />
      <PackagingDialog item={packing} onClose={() => setPacking(null)} />
    </PageContainer>
  );
}
