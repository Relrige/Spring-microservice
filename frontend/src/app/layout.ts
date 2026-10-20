import { useMediaQuery } from '@mantine/hooks';

/** Mantine `sm` (48em = 768px): header navigation vs. burger menu. */
export function useIsDesktop(): boolean {
  return useMediaQuery('(min-width: 48em)', true, { getInitialValueInEffect: false }) ?? true;
}

/** Storefront filter column from 900px up, a drawer below (design-system README). */
export function useWideFilters(): boolean {
  return useMediaQuery('(min-width: 900px)', true, { getInitialValueInEffect: false }) ?? true;
}
