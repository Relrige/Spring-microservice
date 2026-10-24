// The product form of the create and edit pages (spec product-management; design D6).
import {
  Button,
  Group,
  NumberInput,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import type { ProductInput } from '../../api/catalog';
import { ApiError } from '../../api/errors';
import { applyServerErrors, FormError } from '../../components/form-errors';
import { ProductImage } from '../../components/ProductImage';
import { catalogKeys, useCategories } from '../catalog/queries';
import {
  categoryError,
  descriptionError,
  DESCRIPTION_MAX,
  imageUrlError,
  priceError,
  titleError,
  toProductInput,
  type ProductFormValues,
} from './field-rules';
import { CATEGORY_GONE_FOR_PRODUCT, isConflict } from './messages';

const FIELDS = ['title', 'description', 'categoryId', 'basePrice', 'imageUrl'] as const;

export const EMPTY_PRODUCT: ProductFormValues = {
  title: '',
  description: '',
  categoryId: null,
  basePrice: '',
  imageUrl: '',
};

interface ProductFormProps {
  /** Read once at mount: the edit page re-mounts the form only on "Reload product" (design D6). */
  initialValues: ProductFormValues;
  submitLabel: string;
  /** Sends the trimmed body; may return the saved values to show (from the `PUT` answer). */
  onSubmit: (input: ProductInput) => Promise<ProductFormValues | void>;
  /** A `409` overlapping save: the page shows its alert; the entered values stay. */
  onConflict?: () => void;
}

export function ProductForm({
  initialValues,
  submitLabel,
  onSubmit,
  onConflict,
}: ProductFormProps) {
  const queryClient = useQueryClient();
  const categories = useCategories();
  const [general, setGeneral] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const form = useForm<ProductFormValues>({
    initialValues,
    validate: {
      title: titleError,
      description: descriptionError,
      categoryId: categoryError,
      basePrice: priceError,
      imageUrl: imageUrlError,
    },
  });

  const submit = form.onSubmit(async (values) => {
    setGeneral(null);
    setPending(true);
    try {
      const saved = await onSubmit(toProductInput(values));
      if (saved) {
        form.setValues(saved);
        form.resetDirty(saved);
      }
    } catch (error) {
      if (isConflict(error)) onConflict?.();
      else if (error instanceof ApiError && error.status === 422) {
        // Category removed meanwhile: say so at the field and read the categories again.
        form.setFieldError('categoryId', CATEGORY_GONE_FOR_PRODUCT);
        void queryClient.invalidateQueries({ queryKey: catalogKeys.categories });
      } else setGeneral(applyServerErrors(form, error, FIELDS));
    } finally {
      setPending(false);
    }
  });

  const description = form.values.description;
  const image = form.values.imageUrl.trim();
  const categoryName = categories.data?.find((c) => c.categoryId === form.values.categoryId)?.name;
  return (
    <form onSubmit={submit} noValidate>
      <Stack gap="md">
        <FormError message={general} />
        <TextInput label="Title" {...form.getInputProps('title')} />
        <Textarea
          label="Description"
          autosize
          minRows={4}
          maxRows={14}
          {...form.getInputProps('description')}
          description={
            <Text component="span" size="xs" c="dimmed">
              {description.length} / {DESCRIPTION_MAX}
            </Text>
          }
        />
        <Group grow align="flex-start" wrap="wrap">
          <Select
            label="Category"
            placeholder={categories.isPending ? 'Loading categories…' : 'Choose a category'}
            searchable
            nothingFoundMessage="No category matches"
            // One page of 100 categories, as on the storefront (design, Risks).
            data={(categories.data ?? []).map((c) => ({ value: c.categoryId, label: c.name }))}
            {...form.getInputProps('categoryId')}
          />
          <NumberInput
            label="Price"
            leftSection={
              <Text size="sm" c="dimmed">
                UAH
              </Text>
            }
            leftSectionWidth={48}
            decimalScale={2}
            min={0.01}
            clampBehavior="none"
            hideControls
            thousandSeparator=","
            {...form.getInputProps('basePrice')}
          />
        </Group>
        <TextInput
          label="Image"
          description="Optional: an https:// link or a path under /images/"
          placeholder="https://…"
          {...form.getInputProps('imageUrl')}
        />
        {image && !imageUrlError(image) && (
          <div data-testid="image-preview" style={{ width: 160 }}>
            <ProductImage categoryName={categoryName} imageUrl={image} radius={8} />
          </div>
        )}
        <Group>
          <Button type="submit" loading={pending}>
            {submitLabel}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
