import { Anchor, Button, Group, Radio, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useCategories } from './queries';
import { isCatalogPrice, type CatalogState } from './url-state';

interface FilterPanelProps {
  state: CatalogState;
  onChange: (changes: Partial<CatalogState>) => void;
  onClear: () => void;
}

const legendClass = 'vs-label';

/** Category and price filters (spec catalog-browsing, Search and filters). */
export function FilterPanel({ state, onChange, onClear }: FilterPanelProps) {
  const categories = useCategories();

  return (
    <Stack gap="lg">
      <Radio.Group
        value={state.category ?? ''}
        onChange={(value) => onChange({ category: value || null })}
        label={<span className={legendClass}>Category</span>}
        name="category"
      >
        <Stack gap={10} mt={10}>
          <Radio value="" label="All categories" />
          {categories.data?.map((category) => (
            <Radio key={category.categoryId} value={category.categoryId} label={category.name} />
          ))}
          {categories.isPending && (
            <Text size="sm" c="dimmed">
              Loading categories…
            </Text>
          )}
          {categories.isError && (
            <Text size="sm" c="var(--vs-danger)" role="status">
              Categories could not be loaded.
            </Text>
          )}
        </Stack>
      </Radio.Group>

      <PriceFilter
        key={`${state.minPrice}-${state.maxPrice}`}
        minPrice={state.minPrice}
        maxPrice={state.maxPrice}
        onApply={(minPrice, maxPrice) => onChange({ minPrice, maxPrice })}
      />

      <Anchor component="button" type="button" onClick={onClear} size="sm" fw={600} ta="left">
        Clear filters
      </Anchor>
    </Stack>
  );
}

const normalized = (value: string) => value.trim().replace(',', '.');

function inputError(value: string): string | undefined {
  const text = normalized(value);
  if (!text || isCatalogPrice(text)) return undefined;
  if (!/^\d*\.?\d*$/.test(text) || text === '.') return 'Enter a price of 0 or more';
  if (/\.\d{3,}$/.test(text)) return 'Use at most two decimals';
  return 'Use at most 10 digits before the decimal point';
}

const inputValue = (value: string) => (normalized(value) ? Number(normalized(value)) : null);

/**
 * Spec catalog-browsing, Invalid price range: a value Catalog would refuse or min > max
 * is shown at the price fields and the list is not requested with that range.
 */
function PriceFilter({
  minPrice,
  maxPrice,
  onApply,
}: {
  minPrice: number | null;
  maxPrice: number | null;
  onApply: (minPrice: number | null, maxPrice: number | null) => void;
}) {
  const [min, setMin] = useState(minPrice === null ? '' : String(minPrice));
  const [max, setMax] = useState(maxPrice === null ? '' : String(maxPrice));
  const [errors, setErrors] = useState<{ min?: string; max?: string }>({});

  const apply = () => {
    const next = { min: inputError(min), max: inputError(max) };
    const minValue = inputValue(min);
    const maxValue = inputValue(max);
    if (!next.min && !next.max && minValue !== null && maxValue !== null && minValue > maxValue) {
      next.min = 'Minimum must not be greater than maximum';
    }
    setErrors(next);
    if (next.min || next.max) return;
    onApply(minValue, maxValue);
  };

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        apply();
      }}
    >
      <Stack gap={10}>
        <span className={legendClass}>Price, UAH</span>
        <Group gap="sm" wrap="nowrap" align="flex-start">
          <TextInput
            aria-label="Minimum price"
            placeholder="Min"
            inputMode="decimal"
            value={min}
            onChange={(event) => setMin(event.currentTarget.value)}
            error={errors.min}
            style={{ flex: 1, minWidth: 0 }}
          />
          <Text c="dimmed" pt={8} aria-hidden>
            –
          </Text>
          <TextInput
            aria-label="Maximum price"
            placeholder="Max"
            inputMode="decimal"
            value={max}
            onChange={(event) => setMax(event.currentTarget.value)}
            error={errors.max}
            style={{ flex: 1, minWidth: 0 }}
          />
        </Group>
        <Button type="submit" variant="outline">
          Apply price
        </Button>
      </Stack>
    </form>
  );
}
