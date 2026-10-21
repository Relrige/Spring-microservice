import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { db } from '../../mocks/db';
import { renderApp } from '../../test/render';
import { server } from '../../test/server';

describe('Product details page', () => {
  it('Active product: title, description, price, category name and stock status', async () => {
    const product = db.products.find((p) => p.title === 'Sony WH-1000XM5 headphones')!;
    renderApp(`/products/${product.productId}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: product.title }),
    ).toBeInTheDocument();
    expect(screen.getByText(product.description)).toBeInTheDocument();
    expect(screen.getByText('UAH 12,999.00')).toBeInTheDocument();
    expect(screen.getByText('In stock')).toBeInTheDocument();
    expect(await screen.findByTestId('product-category')).toHaveTextContent('Audio');
    expect(screen.getByRole('img', { name: 'Audio placeholder image' })).toBeInTheDocument();
  });

  it('Missing or inactive product: "This product is not available" with a link back', async () => {
    const inactive = db.products.find((p) => p.status === 'NOT_ACTIVE')!;
    renderApp(`/products/${inactive.productId}`);
    expect(await screen.findByText('This product is not available')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to the catalog' })).toHaveAttribute('href', '/');
  });

  it('Non-UUID id: also "not available"', async () => {
    renderApp('/products/not-a-uuid');
    expect(await screen.findByText('This product is not available')).toBeInTheDocument();
  });

  it('Unknown category: the category is omitted and the neutral placeholder shown', async () => {
    const product = db.products.find((p) => p.status === 'ACTIVE')!;
    server.use(
      http.get('*/api/catalog/categories', () =>
        HttpResponse.json({ content: [], page: 0, totalElements: 0 }),
      ),
    );
    renderApp(`/products/${product.productId}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: product.title }),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('product-category')).not.toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Product placeholder image' })).toBeInTheDocument();
  });

  it('shows the unavailable message with Try again on 503', async () => {
    const product = db.products.find((p) => p.status === 'ACTIVE')!;
    server.use(
      http.get(
        '*/api/catalog/products/:id',
        () => new HttpResponse(null, { status: 503, headers: { 'Retry-After': '10' } }),
      ),
    );
    renderApp(`/products/${product.productId}`);
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 10 seconds');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
