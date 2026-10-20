import { Anchor, Button, Group, Radio, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useCategories } from './queries';
import type { CatalogState } from './url-state';

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

function parseInput(value: string): number | null | 'invalid' {
  const trimmed = value.trim().replace(',', '.');
  if (!trimmed) return null;
  const number = Number(trimmed);
  return Number.isFinite(number) ? number : 'invalid';
}

/**
 * Spec catalog-browsing, Invalid price range: a negative value or min > max is
 * shown at the price fields and the list is not requested with that range.
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
    const minValue = parseInput(min);
    const maxValue = parseInput(max);
    const next: { min?: string; max?: string } = {};
    if (minValue === 'invalid' || (typeof minValue === 'number' && minValue < 0)) {
      next.min = 'Enter a price of 0 or more';
    }
    if (maxValue === 'invalid' || (typeof maxValue === 'number' && maxValue < 0)) {
      next.max = 'Enter a price of 0 or more';
    }
    if (
      !next.min &&
      !next.max &&
      typeof minValue === 'number' &&
      typeof maxValue === 'number' &&
      minValue > maxValue
    ) {
      next.min = 'Minimum must not be greater than maximum';
    }
    setErrors(next);
    if (next.min || next.max) return;
    onApply(minValue as number | null, maxValue as number | null);
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
