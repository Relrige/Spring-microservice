import { Button, Group, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { catalogUrl, DEFAULT_STATE, parseCatalogState } from './url-state';

/**
 * Keyword search of the product list (spec catalog-browsing, Search and filters).
 * Updates the URL on submit, not on every keystroke (design D8), and starts at page 1.
 */
export function SearchForm({ size = 'sm' }: { size?: 'sm' | 'md' }) {
  const [params] = useSearchParams();
  const { pathname } = useLocation();
  const current = pathname === '/' ? parseCatalogState(params) : DEFAULT_STATE;
  // Remounting on a URL change keeps the input equal to the URL (back/forward).
  return <SearchFormInner key={current.search} initial={current.search} size={size} />;
}

function SearchFormInner({ initial, size }: { initial: string; size: 'sm' | 'md' }) {
  const [value, setValue] = useState(initial);
  const [params] = useSearchParams();
  const { pathname } = useLocation();
  const navigate = useNavigate();

  return (
    <form
      role="search"
      style={{ flex: '1 1 320px', minWidth: 0 }}
      onSubmit={(event) => {
        event.preventDefault();
        const base = pathname === '/' ? parseCatalogState(params) : DEFAULT_STATE;
        navigate(catalogUrl({ ...base, search: value.trim(), page: 1 }));
      }}
    >
      <Group gap="sm" wrap="nowrap">
        <TextInput
          type="search"
          aria-label="Search products"
          placeholder="Search phones, laptops, headphones…"
          value={value}
          onChange={(event) => setValue(event.currentTarget.value)}
          style={{ flex: '1 1 auto', minWidth: 0 }}
          size={size === 'md' ? 'md' : 'sm'}
          h={size === 'md' ? 44 : 40}
          styles={{ input: { height: size === 'md' ? 44 : 40 } }}
        />
        <Button type="submit" h={size === 'md' ? 44 : 40}>
          Search
        </Button>
      </Group>
    </form>
  );
}
