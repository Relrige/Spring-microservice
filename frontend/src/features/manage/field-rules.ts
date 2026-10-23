// Catalog's field rules, checked before sending (specs product-management, Product field
// rules; category-management, Category name rules). Limits from catalog public.json.
import type { ProductInput } from '../../api/catalog';

export const TITLE_MAX = 200;
export const DESCRIPTION_MAX = 5000;
export const CATEGORY_NAME_MAX = 100;

/** The product form; field names are the schema's, so `400` `errors` keys match (design D6). */
export interface ProductFormValues {
  title: string;
  description: string;
  categoryId: string | null;
  /** `''` while the price input is empty. */
  basePrice: number | string;
}

const text = (label: string, max: number) => (value: string) =>
  !value.trim()
    ? `Enter ${label}`
    : value.trim().length > max
      ? `Use at most ${max} characters`
      : null;

export const titleError = text('a title', TITLE_MAX);
export const descriptionError = text('a description', DESCRIPTION_MAX);
export const categoryError = (value: string | null) => (value ? null : 'Choose a category');

export function priceError(value: number | string): string | null {
  if (value === '' || typeof value !== 'number' || !Number.isFinite(value)) return 'Enter a price';
  if (value <= 0) return 'The price must be greater than 0';
  if (Math.round(value * 100) !== value * 100) return 'Use at most two decimals';
  return null;
}

export const categoryNameError = text('a name', CATEGORY_NAME_MAX);

/** The trimmed request body of valid form values. */
export function toProductInput(values: ProductFormValues): ProductInput {
  return {
    title: values.title.trim(),
    description: values.description.trim(),
    categoryId: values.categoryId!,
    basePrice: Number(values.basePrice),
  };
}
