import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { db } from '../../mocks/db';
import { recordCartRequests } from '../../test/cart';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';
import { problem } from '../../mocks/handlers';
import { submitLogin } from '../../test/ui';
import { writeGuestCart } from './guest-cart';

const product = (title: string) => db.products.find((p) => p.title === title)!;
const customerId = () => db.users.find((u) => u.role === 'CUSTOMER')!.id;
const SONY = 'Sony WH-1000XM5 headphones';
const ANKER = 'Anker 737 power bank, 24,000 mAh';

function guestCart(...lines: [string, number][]) {
  writeGuestCart(
    lines
      .map(([title, quantity]) => ({ productId: product(title).productId, quantity }))
      .sort((a, b) => (a.productId < b.productId ? -1 : 1)),
  );
}

async function lineOf(name: string | RegExp) {
  const text = await screen.findByText(name);
  return text.closest<HTMLElement>('[data-testid="cart-line"]')!;
}

describe('Cart page lines', () => {
  it('Cart with two products: title, unit price, quantity and line totals', async () => {
    guestCart([SONY, 1], [ANKER, 2]);
    renderApp('/cart');
    const sony = await lineOf(SONY);
    expect(within(sony).getByText('UAH 12,999.00 each')).toBeInTheDocument();
    expect(within(sony).getByTestId('line-quantity')).toHaveTextContent('1');
    expect(within(sony).getByText('UAH 12,999.00')).toBeInTheDocument();
    const anker = await lineOf(ANKER);
    expect(within(anker).getByText('UAH 4,899.00 each')).toBeInTheDocument();
    expect(within(anker).getByTestId('line-quantity')).toHaveTextContent('2');
    expect(within(anker).getByText('UAH 9,798.00')).toBeInTheDocument();
    expect(within(sony).getByRole('link', { name: SONY })).toHaveAttribute(
      'href',
      `/products/${product(SONY).productId}`,
    );
  });

  it('Product details cannot be loaded: Try again on that line, the others shown', async () => {
    guestCart([SONY, 1], [ANKER, 2]);
    const failing = product(ANKER).productId;
    server.use(
      http.get(`*/api/catalog/products/${failing}`, () => new HttpResponse(null, { status: 503 })),
    );
    renderApp('/cart');
    const failed = await lineOf('Product details could not be loaded');
    expect(within(failed).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(within(failed).getByRole('button', { name: 'Remove' })).toBeInTheDocument();
    expect(await lineOf(SONY)).toBeInTheDocument();
    expect(screen.getByText(/subtotal is incomplete/)).toBeInTheDocument();

    server.resetHandlers();
    await userEvent.click(within(failed).getByRole('button', { name: 'Try again' }));
    expect(await lineOf(ANKER)).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByText(/subtotal is incomplete/)).not.toBeInTheDocument(),
    );
  });
});

describe('Unavailable cart lines', () => {
  it('Product deactivated after it was added: "Product no longer available", removable', async () => {
    guestCart([SONY, 1], ['Xiaomi 14T, 512 GB', 1]);
    renderApp('/cart');
    const gone = await lineOf('Product no longer available');
    expect(within(gone).getByText('Unavailable')).toBeInTheDocument();
    expect(within(gone).getByText('Not counted in the subtotal')).toBeInTheDocument();
    await userEvent.click(within(gone).getByRole('button', { name: 'Remove' }));
    await waitFor(() =>
      expect(screen.queryByText('Product no longer available')).not.toBeInTheDocument(),
    );
    expect(screen.getAllByTestId('cart-line')).toHaveLength(1);
  });

  it('Product sold out after it was added: title kept, Unavailable, not in the subtotal', async () => {
    const soldOut = db.products.find(
      (p) => p.status === 'ACTIVE' && p.stockStatus === 'OUT_OF_STOCK',
    )!;
    guestCart([SONY, 1], [soldOut.title, 2]);
    renderApp('/cart');
    const line = await lineOf(soldOut.title);
    expect(within(line).getByText('Unavailable')).toBeInTheDocument();
    expect(
      within(line).queryByRole('button', { name: 'Increase quantity' }),
    ).not.toBeInTheDocument();
    await lineOf(SONY);
    expect(screen.getByTestId('summary-items')).toHaveTextContent('1');
    expect(screen.getByTestId('summary-subtotal')).toHaveTextContent('UAH 12,999.00');
  });
});

describe('Change quantity and remove', () => {
  it('Increase a quantity: PATCH with quantity 3 and the line shows 3', async () => {
    loginAs('CUSTOMER');
    renderApp('/cart');
    const anker = await lineOf(ANKER);
    const calls = recordCartRequests();
    await userEvent.click(within(anker).getByRole('button', { name: 'Increase quantity' }));
    await waitFor(() => expect(within(anker).getByTestId('line-quantity')).toHaveTextContent('3'));
    expect(calls).toContain(`PATCH /customers/cart/items/${product(ANKER).productId}`);
  });

  it('Stepper bounds: "−" disabled at 1, "+" disabled at 10', async () => {
    guestCart([SONY, 1], [ANKER, 10]);
    renderApp('/cart');
    const sony = await lineOf(SONY);
    expect(within(sony).getByRole('button', { name: 'Decrease quantity' })).toBeDisabled();
    expect(within(sony).getByRole('button', { name: 'Increase quantity' })).toBeEnabled();
    const anker = await lineOf(ANKER);
    expect(within(anker).getByRole('button', { name: 'Increase quantity' })).toBeDisabled();
  });

  it('Remove a line: it leaves the cart and the item count', async () => {
    guestCart([SONY, 1], [ANKER, 2]);
    renderApp('/cart');
    await lineOf(ANKER);
    await lineOf(SONY);
    expect(screen.getByTestId('summary-items')).toHaveTextContent('3');
    await userEvent.click(screen.getByRole('button', { name: `Remove ${SONY}` }));
    await waitFor(() => expect(screen.queryByText(SONY)).not.toBeInTheDocument());
    expect(screen.getByTestId('summary-items')).toHaveTextContent('2');
  });
});

describe('Clear the cart', () => {
  it('Clear after confirming: DELETE /customers/cart and the empty state', async () => {
    loginAs('CUSTOMER');
    renderApp('/cart');
    await lineOf(SONY);
    const calls = recordCartRequests();
    await userEvent.click(screen.getByRole('button', { name: 'Clear cart' }));
    const dialog = await screen.findByRole('dialog', { name: 'Clear the cart?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Clear cart' }));
    expect(await screen.findByText('Your cart is empty')).toBeInTheDocument();
    expect(calls).toContain('DELETE /customers/cart');
  });

  it('Clear cancelled: the cart is unchanged and no request is sent', async () => {
    loginAs('CUSTOMER');
    renderApp('/cart');
    await lineOf(SONY);
    const calls = recordCartRequests();
    await userEvent.click(screen.getByRole('button', { name: 'Clear cart' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(screen.getAllByTestId('cart-line')).toHaveLength(3);
    expect(calls).toEqual([]);
  });
});

describe('Empty cart', () => {
  it('New visitor opens the cart: "Your cart is empty" and a link to the catalog', async () => {
    renderApp('/cart');
    expect(await screen.findByText('Your cart is empty')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse the catalog' })).toHaveAttribute('href', '/');
  });
});

describe('Cart summary and checkout entry', () => {
  it('Summary with an unavailable line: Items 3, estimated subtotal UAH 22,797.00', async () => {
    loginAs('CUSTOMER');
    renderApp('/cart');
    await lineOf('Product no longer available');
    await lineOf(ANKER);
    await lineOf(SONY);
    expect(screen.getByTestId('summary-items')).toHaveTextContent('3');
    expect(screen.getByTestId('summary-subtotal')).toHaveTextContent('UAH 22,797.00');
    expect(screen.getByRole('button', { name: 'Proceed to checkout' })).toBeDisabled();
    expect(screen.getByText('Remove unavailable products to check out.')).toBeInTheDocument();
  });

  it('Customer proceeds to checkout: the soft check passes and the checkout opens', async () => {
    db.carts.set(customerId(), [{ productId: product(SONY).productId, quantity: 1 }]);
    loginAs('CUSTOMER');
    const calls: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.endsWith('/orders/validate')) calls.push(request.method);
    });
    const { router } = renderApp('/cart');
    await lineOf(SONY);
    await userEvent.click(screen.getByRole('button', { name: 'Proceed to checkout' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/checkout'));
    expect(calls).toEqual(['POST']);
  });

  it('Unavailable line blocks checkout for a customer', async () => {
    loginAs('CUSTOMER');
    renderApp('/cart');
    await lineOf('Product no longer available');
    await lineOf(SONY);
    expect(screen.getByRole('button', { name: 'Proceed to checkout' })).toBeDisabled();
    expect(screen.getByText('Remove unavailable products to check out.')).toBeInTheDocument();
  });

  it('Product became unavailable: the soft check names it and the cart stays', async () => {
    db.carts.set(customerId(), [{ productId: product(SONY).productId, quantity: 1 }]);
    server.use(
      http.post('*/api/orders/validate', () =>
        problem(
          422,
          'Some items cannot be ordered',
          {
            ineligibleItems: [{ productId: product(SONY).productId, reason: 'INSUFFICIENT_STOCK' }],
          },
          'order-service',
          'ineligible-items',
        ),
      ),
    );
    loginAs('CUSTOMER');
    const { router } = renderApp('/cart');
    await lineOf(SONY);
    await userEvent.click(screen.getByRole('button', { name: 'Proceed to checkout' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      `${SONY} is out of stock. Remove it to check out.`,
    );
    expect(currentUrl(router)).toBe('/cart');
  });

  it('Order Service unavailable at the check: the message, and the checkout does not open', async () => {
    db.carts.set(customerId(), [{ productId: product(SONY).productId, quantity: 1 }]);
    server.use(http.post('*/api/orders/validate', () => new HttpResponse(null, { status: 503 })));
    loginAs('CUSTOMER');
    const { router } = renderApp('/cart');
    await lineOf(SONY);
    await userEvent.click(screen.getByRole('button', { name: 'Proceed to checkout' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This service is temporarily unavailable.',
    );
    expect(currentUrl(router)).toBe('/cart');
  });

  it('Visitor proceeds to checkout: login, then back to /cart', async () => {
    db.users.push({
      id: crypto.randomUUID(),
      email: 'new@example.com',
      password: 'secret',
      role: 'CUSTOMER',
    });
    guestCart([SONY, 1]);
    const { router } = renderApp('/cart');
    await lineOf(SONY);
    await userEvent.click(screen.getByRole('link', { name: 'Proceed to checkout' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/login?returnTo=%2Fcart'));
    await submitLogin('new@example.com', 'secret');
    await waitFor(() => expect(currentUrl(router)).toBe('/cart'));
  });
});
