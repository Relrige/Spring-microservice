import { Box, Button, Drawer, Group, NativeSelect, Stack, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconAdjustmentsHorizontal } from '@tabler/icons-react';
import { useSearchParams } from 'react-router';
import type { SortBy } from '../../api/catalog';
import { useIsDesktop, useWideFilters } from '../../app/layout';
import { PageContainer } from '../../app/PageContainer';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { FilterPanel } from './FilterPanel';
import { Paging } from './Paging';
import { ProductTile } from './ProductTile';
import { useCategoryNames, useProducts } from './queries';
import { SearchForm } from './SearchForm';
import {
  DEFAULT_STATE,
  parseCatalogState,
  serializeCatalogState,
  SORT_OPTIONS,
  type CatalogState,
} from './url-state';

/** `/`: the storefront product list (spec catalog-browsing; mockups Main and StorefrontPhone). */
export function ProductListPage() {
  const [params, setParams] = useSearchParams();
  const state = parseCatalogState(params);
  const products = useProducts(state);
  const categoryNames = useCategoryNames();
  const isDesktop = useIsDesktop();
  const wideFilters = useWideFilters();
  const [filtersOpen, filters] = useDisclosure(false);

  // Every change except paging returns to the first page (spec, Search and filters).
  const update = (changes: Partial<CatalogState>) => {
    const next = { ...state, ...changes, page: 'page' in changes ? changes.page! : 1 };
    setParams(serializeCatalogState(next));
  };
  const clearFilters = () => {
    setParams(serializeCatalogState(DEFAULT_STATE));
    filters.close();
  };

  const categoryName = state.category ? categoryNames?.get(state.category) : undefined;
  const title = state.search
    ? `Results for “${state.search}”`
    : (categoryName ?? (state.category ? 'Category' : 'All products'));
  const total = products.data?.totalElements;
  // The page size comes from the answer (paging envelope); there is no totalPages.
  const totalPages = products.data
    ? Math.max(1, Math.ceil(products.data.totalElements / products.data.size))
    : 1;
  const filtered =
    state.search !== '' ||
    state.category !== null ||
    state.minPrice !== null ||
    state.maxPrice !== null;

  const panel = (
    <FilterPanel
      state={state}
      onChange={(changes) => {
        update(changes);
        filters.close();
      }}
      onClear={clearFilters}
    />
  );

  return (
    <PageContainer>
      <Stack gap="lg">
        {!isDesktop && (
          <Group gap="sm" wrap="nowrap">
            <SearchForm size="md" />
          </Group>
        )}

        <Group justify="space-between" align="flex-end" gap="md">
          <Stack gap={4}>
            <Title order={1}>{title}</Title>
            <Text size="sm" c="dimmed" aria-live="polite">
              {total === undefined ? ' ' : `${total} product${total === 1 ? '' : 's'}`}
            </Text>
          </Stack>
          <Group gap="sm" align="center">
            {!wideFilters && (
              <Button
                variant="default"
                leftSection={<IconAdjustmentsHorizontal size={20} stroke={1.75} />}
                onClick={filters.open}
              >
                Filters
              </Button>
            )}
            <NativeSelect
              label="Sort by"
              value={state.sort}
              data={SORT_OPTIONS}
              onChange={(event) => update({ sort: event.currentTarget.value as SortBy })}
              styles={{
                root: { display: 'flex', alignItems: 'center', gap: 8 },
                label: { fontSize: 13, color: 'var(--vs-ink-muted)', fontWeight: 400 },
              }}
            />
          </Group>
        </Group>

        <Group align="flex-start" gap="lg" wrap="nowrap">
          {wideFilters && (
            <Box
              component="aside"
              aria-label="Filters"
              w="var(--vs-filter-panel)"
              p={20}
              className="vs-sunken"
              style={{ flex: '0 0 auto', borderRadius: 10 }}
            >
              {panel}
            </Box>
          )}

          <Stack
            component="section"
            aria-label="Products"
            gap="lg"
            style={{ flex: 1, minWidth: 0 }}
          >
            {products.isPending && <Loading lines={6} label="Loading products…" />}
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
                ) : (
                  <>
                    <Title order={3}>No products match your filters</Title>
                    <Text c="dimmed">Try other words or a wider price range.</Text>
                    {filtered && (
                      <Button variant="outline" onClick={clearFilters}>
                        Clear filters
                      </Button>
                    )}
                  </>
                )}
              </Stack>
            )}
            {products.isSuccess && products.data.content.length > 0 && (
              <>
                <Box
                  style={{
                    display: 'grid',
                    gridTemplateColumns: isDesktop
                      ? 'repeat(auto-fill, minmax(200px, 1fr))'
                      : 'repeat(auto-fill, minmax(260px, 1fr))',
                    gap: isDesktop ? 24 : 16,
                  }}
                >
                  {products.data.content.map((product) => (
                    <ProductTile
                      key={product.productId}
                      product={product}
                      categoryName={categoryNames?.get(product.categoryId)}
                      compact={!isDesktop}
                    />
                  ))}
                </Box>
                <Paging
                  page={state.page}
                  totalPages={totalPages}
                  onPage={(page) => {
                    update({ page });
                    window.scrollTo?.({ top: 0 });
                  }}
                />
              </>
            )}
          </Stack>
        </Group>
      </Stack>

      {!wideFilters && (
        <Drawer
          opened={filtersOpen}
          onClose={filters.close}
          title="Filters"
          position="left"
          size={300}
        >
          {panel}
        </Drawer>
      )}
    </PageContainer>
  );
}
