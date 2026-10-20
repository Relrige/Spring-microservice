import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '../test/render';
import { formatPrice, Price } from './Price';
import { placeholderFor, ProductImage } from './ProductImage';
import { StockBadge } from './StatusChip';

describe('Price', () => {
  it('formats 1299 as UAH 1,299.00', () => {
    expect(formatPrice(1299)).toBe('UAH 1,299.00');
    expect(formatPrice(399.5)).toBe('UAH 399.50');
    renderWithProviders(<Price value={37999} />);
    expect(screen.getByText('UAH 37,999.00')).toBeInTheDocument();
  });
});

describe('StockBadge', () => {
  it('labels both stock states with words', () => {
    renderWithProviders(
      <>
        <StockBadge status="IN_STOCK" />
        <StockBadge status="OUT_OF_STOCK" />
      </>,
    );
    expect(screen.getByText('In stock')).toBeInTheDocument();
    expect(screen.getByText('Out of stock')).toBeInTheDocument();
  });
});

describe('Product image placeholder', () => {
  it('Same category, same placeholder; different categories differ', () => {
    renderWithProviders(
      <>
        <ProductImage categoryName="Phones" />
        <ProductImage categoryName="Phones" />
        <ProductImage categoryName="Laptops" />
      </>,
    );
    const [phoneA, phoneB, laptop] = screen.getAllByRole('img');
    expect(phoneA!.dataset.placeholder).toBe('phones');
    expect(phoneB!.getAttribute('style')).toBe(phoneA!.getAttribute('style'));
    expect(laptop!.dataset.placeholder).toBe('laptops');
    expect(laptop!.getAttribute('style')).not.toBe(phoneA!.getAttribute('style'));
  });

  it('Unknown category: the neutral default placeholder', () => {
    renderWithProviders(<ProductImage categoryName={undefined} />);
    const image = screen.getByRole('img', { name: 'Product placeholder image' });
    expect(image.dataset.placeholder).toBe('unknown');
    expect(image.style.background).toBe('var(--vs-surface-sunken)');
  });

  it('gives other category names a stable slot', () => {
    expect(placeholderFor('Cameras')).toEqual(placeholderFor('cameras'));
    expect(placeholderFor('Cameras').kind).toMatch(/^other:\d$/);
  });
});
