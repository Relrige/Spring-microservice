// `/manage/products/new` (spec product-management, Create a product; design D6).
import { Anchor, Stack, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router';
import { createProduct } from '../../api/catalog';
import { PageContainer } from '../../app/PageContainer';
import { EMPTY_PRODUCT, ProductForm } from './ProductForm';
import { invalidateCatalog } from './queries';

export function ProductCreatePage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  return (
    <PageContainer>
      <Stack gap="lg" maw={720}>
        <Anchor component={Link} to="/manage/products" size="sm">
          ← Products
        </Anchor>
        <Stack gap={4}>
          <Title order={1}>New product</Title>
          <Text c="dimmed">A new product stays inactive until you activate it.</Text>
        </Stack>
        <ProductForm
          initialValues={EMPTY_PRODUCT}
          submitLabel="Create product"
          onSubmit={async (input) => {
            const { productId } = await createProduct(input);
            void invalidateCatalog(queryClient);
            // The edit page shows the "Product created…" notice once (design D6).
            navigate(`/manage/products/${productId}`, { state: { created: true } });
          }}
        />
      </Stack>
    </PageContainer>
  );
}
