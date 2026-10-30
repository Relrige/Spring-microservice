import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { db } from '../../mocks/db';
import { recordCartRequests } from '../../test/cart';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { readGuestCart, writeGuestCart } from './guest-cart';

const inStock = () =>
  db.products.find((p) => p.status === 'ACTIVE' && p.stockStatus === 'IN_STOCK')!;

describe('Add to cart from the product page', () => {
  it('Visitor adds two: the cart holds 2, "Added to cart" links to the cart', async () => {
    const product = inStock();
    const { router } = renderApp(`/products/${product.productId}`);
    const quantity = await screen.findByLabelText('Quantity');
    expect(quantity).toHaveValue('1');
    await userEvent.clear(quantity);
    await userEvent.type(quantity, '2');
    await userEvent.click(screen.getByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByText('Added to cart')).toBeInTheDocument();
    expect(readGuestCart()).toEqual([{ productId: product.productId, quantity: 2 }]);
    expect(screen.getByTestId('cart-count')).toHaveTextContent('2');

    await userEvent.click(screen.getByRole('link', { name: 'View cart' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/cart'));
  });

  it('Empty quantity: asks for a quantity and "Add to cart" is disabled until one is entered', async () => {
    const product = inStock();
    renderApp(`/products/${product.productId}`);
    const quantity = await screen.findByLabelText('Quantity');
    await userEvent.clear(quantity);
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a quantity from 1 to 10');
    expect(screen.getByRole('button', { name: 'Add to cart' })).toBeDisabled();

    await userEvent.type(quantity, '3');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByText('Added to cart')).toBeInTheDocument();
    expect(readGuestCart()).toEqual([{ productId: product.productId, quantity: 3 }]);
  });

  it('Out-of-stock product: the "Add to cart" button is disabled', async () => {
    const product = db.products.find(
      (p) => p.status === 'ACTIVE' && p.stockStatus === 'OUT_OF_STOCK',
    )!;
    renderApp(`/products/${product.productId}`);
    expect(await screen.findByRole('button', { name: 'Add to cart' })).toBeDisabled();
  });

  it('Manager sees no cart action: no quantity selector and no button', async () => {
    loginAs('CATALOG_MANAGER');
    const product = inStock();
    renderApp(`/products/${product.productId}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: product.title }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Quantity')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add to cart' })).not.toBeInTheDocument();
  });

  it('Add is rejected by a limit: the message on the page and the cart unchanged', async () => {
    const product = inStock();
    writeGuestCart([{ productId: product.productId, quantity: 8 }]);
    renderApp(`/products/${product.productId}`);
    const quantity = await screen.findByLabelText('Quantity');
    await userEvent.clear(quantity);
    await userEvent.type(quantity, '3');
    await userEvent.click(screen.getByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'You can have up to 10 of each product in your cart.',
    );
    expect(readGuestCart()).toEqual([{ productId: product.productId, quantity: 8 }]);
  });

  it('a customer adds through Customer Service', async () => {
    loginAs('CUSTOMER');
    const calls = recordCartRequests();
    renderApp(`/products/${inStock().productId}`);
    await userEvent.click(await screen.findByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByText('Added to cart')).toBeInTheDocument();
    expect(calls).toContain('POST /customers/cart/items');
  });
});
