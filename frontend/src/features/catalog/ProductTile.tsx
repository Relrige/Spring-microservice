import { Group, Stack, Text, UnstyledButton } from '@mantine/core';
import { Link } from 'react-router';
import type { ProductSummary } from '../../api/catalog';
import { Price } from '../../components/Price';
import { ProductImage } from '../../components/ProductImage';
import { StockBadge } from '../../components/StatusChip';
import classes from './ProductTile.module.css';

interface ProductTileProps {
  product: ProductSummary;
  /** `undefined` while categories load or when they failed: the neutral placeholder. */
  categoryName: string | undefined;
  /** Phone layout: a row with a small square image (mockup StorefrontPhone). */
  compact?: boolean;
}

export function ProductTile({ product, categoryName, compact = false }: ProductTileProps) {
  return (
    <UnstyledButton
      component={Link}
      to={`/products/${product.productId}`}
      className={compact ? `${classes.tile} ${classes.row}` : classes.tile}
      data-testid="product-tile"
    >
      {compact ? (
        <ProductImage
          categoryName={categoryName}
          imageUrl={product.imageUrl}
          size={88}
          radius={6}
        />
      ) : (
        <ProductImage categoryName={categoryName} imageUrl={product.imageUrl} radius={0} />
      )}
      <Stack gap="sm" p={compact ? 0 : 'md'} style={{ flex: '1 1 auto', minWidth: 0 }}>
        <Text component="h2" className={classes.title} m={0}>
          {product.title}
        </Text>
        <Group justify="space-between" gap="sm" mt="auto" wrap="wrap">
          <Price value={product.basePrice} />
          <StockBadge status={product.stockStatus} />
        </Group>
      </Stack>
    </UnstyledButton>
  );
}
