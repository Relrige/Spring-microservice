import { Anchor, Box, Group, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from 'react-router';
import { ApiError } from '../../api/errors';
import { PageContainer } from '../../app/PageContainer';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { Price } from '../../components/Price';
import { ProductImage } from '../../components/ProductImage';
import { StockBadge } from '../../components/StatusChip';
import { useCategoryNames, useProduct } from './queries';
import { catalogUrl, DEFAULT_STATE } from './url-state';

/** `/products/:productId` (spec catalog-browsing, Product details page; mockup Product). */
export function ProductDetailsPage() {
  const { productId = '' } = useParams();
  const product = useProduct(productId);
  const categoryNames = useCategoryNames();

  if (product.isPending) {
    return (
      <PageContainer>
        <Loading lines={6} label="Loading product…" />
      </PageContainer>
    );
  }

  if (product.isError) {
    // Unknown, inactive and non-UUID ids are all answered 404.
    if (product.error instanceof ApiError && product.error.kind === 'not-found') {
      return (
        <PageContainer>
          <Stack align="center" gap="sm" py={48} ta="center">
            <Title order={1}>This product is not available</Title>
            <Text c="dimmed">It may have been removed from the catalog.</Text>
            <Anchor component={Link} to="/" fw={600}>
              Back to the catalog
            </Anchor>
          </Stack>
        </PageContainer>
      );
    }
    return (
      <PageContainer>
        <ErrorState error={product.error} onRetry={() => void product.refetch()} />
      </PageContainer>
    );
  }

  const data = product.data;
  // The category name comes from the categories list; omitted when it cannot be found.
  const categoryName = categoryNames?.get(data.categoryId);
  const paragraphs = data.description.split(/\n\s*\n/).filter((p) => p.trim());

  return (
    <PageContainer pt={{ base: 'md', sm: 'lg' }}>
      <Stack gap="lg">
        <Group component="nav" aria-label="Breadcrumb" gap="sm" fz="sm" c="dimmed" wrap="wrap">
          <Anchor component={Link} to="/" fz="sm">
            Catalog
          </Anchor>
          {categoryName && (
            <>
              <span aria-hidden="true">/</span>
              <Anchor
                component={Link}
                to={catalogUrl({ ...DEFAULT_STATE, category: data.categoryId })}
                fz="sm"
              >
                {categoryName}
              </Anchor>
            </>
          )}
          <span aria-hidden="true">/</span>
          <Text span fz="sm" c="var(--vs-ink)" aria-current="page">
            {data.title}
          </Text>
        </Group>

        <Group align="flex-start" gap={40} wrap="wrap">
          <Box style={{ flex: '1 1 420px', minWidth: 0, maxWidth: '100%' }}>
            <ProductImage categoryName={categoryName} radius={16} />
          </Box>

          <Stack gap={20} style={{ flex: '1 1 380px', minWidth: 0 }}>
            <Stack gap="sm">
              {categoryName && (
                <span className="vs-label" data-testid="product-category">
                  {categoryName}
                </span>
              )}
              <Title order={1}>{data.title}</Title>
            </Stack>
            <Group gap="md" wrap="wrap">
              <Price value={data.basePrice} large />
              <StockBadge status={data.stockStatus} />
            </Group>
            <Stack gap="sm" pt={20} maw="68ch" style={{ borderTop: '1px solid var(--vs-line)' }}>
              <Title order={2}>Description</Title>
              {paragraphs.map((paragraph, index) => (
                <Text key={index}>{paragraph}</Text>
              ))}
            </Stack>
          </Stack>
        </Group>
      </Stack>
    </PageContainer>
  );
}
