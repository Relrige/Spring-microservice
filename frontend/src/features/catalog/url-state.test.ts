import { describe, expect, it } from 'vitest';
import {
  DEFAULT_STATE,
  parseCatalogState,
  serializeCatalogState,
  toProductQuery,
  type CatalogState,
} from './url-state';

const CATEGORY = '00000000-0000-4000-a000-000000000004';

describe('catalog URL state', () => {
  it('uses the defaults for an empty query', () => {
    expect(parseCatalogState(new URLSearchParams())).toEqual(DEFAULT_STATE);
  });

  it('Shared link: reads search, sort and page', () => {
    const state = parseCatalogState(new URLSearchParams('search=tv&sort=basePrice,desc&page=2'));
    expect(state).toEqual({ ...DEFAULT_STATE, search: 'tv', sort: 'basePrice,desc', page: 2 });
    expect(toProductQuery(state)).toMatchObject({
      search: 'tv',
      sortBy: 'basePrice,desc',
      page: 1,
      size: 20,
    });
  });

  it('Invalid URL value: page=-3 and an unknown sort fall back to the defaults', () => {
    const state = parseCatalogState(new URLSearchParams('page=-3&sort=color,up'));
    expect(state.page).toBe(1);
    expect(state.sort).toBe('title,asc');
    expect(parseCatalogState(new URLSearchParams('page=abc')).page).toBe(1);
    expect(parseCatalogState(new URLSearchParams('page=0')).page).toBe(1);
  });

  it('drops an invalid category, negative prices and an inverted range', () => {
    expect(parseCatalogState(new URLSearchParams('category=phones')).category).toBeNull();
    expect(parseCatalogState(new URLSearchParams('minPrice=-5')).minPrice).toBeNull();
    expect(parseCatalogState(new URLSearchParams('minPrice=0.111')).minPrice).toBeNull();
    expect(parseCatalogState(new URLSearchParams('maxPrice=12345678901')).maxPrice).toBeNull();
    expect(parseCatalogState(new URLSearchParams('minPrice=0.11')).minPrice).toBe(0.11);
    const inverted = parseCatalogState(new URLSearchParams('minPrice=100&maxPrice=50'));
    expect([inverted.minPrice, inverted.maxPrice]).toEqual([null, null]);
  });

  it('round-trips every value and leaves defaults out of the URL', () => {
    const state: CatalogState = {
      search: 'usb c',
      category: CATEGORY,
      minPrice: 100,
      maxPrice: 2500.5,
      sort: 'title,desc',
      page: 3,
    };
    const query = serializeCatalogState(state);
    expect(parseCatalogState(new URLSearchParams(query.toString()))).toEqual(state);
    expect(serializeCatalogState(DEFAULT_STATE).toString()).toBe('');
    expect(serializeCatalogState({ ...DEFAULT_STATE, page: 2 }).toString()).toBe('page=2');
  });
});
