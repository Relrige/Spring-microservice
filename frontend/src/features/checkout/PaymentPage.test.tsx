import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { formatTime } from '../../components/DateTime';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { recordCartRequests } from '../../test/cart';
import { addOrder } from '../../test/orders';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

async function openPayment(orderId: string) {
  loginAs('CUSTOMER');
  const rendered = renderApp(`/checkout/${orderId}`);
  await screen.findByRole('heading', { level: 2, name: 'Delivery details' });
  return rendered;
}

async function fillCard(number: string, expiry = '12/30', cvv = '123') {
  await userEvent.type(await screen.findByLabelText('Card number'), number);
  await userEvent.type(screen.getByLabelText('Expiry (MM/YY)'), expiry);
  await userEvent.type(screen.getByLabelText('CVV'), cvv);
}

const pay = () => userEvent.click(screen.getByRole('button', { name: /^Pay UAH/ }));

const payAnswer = (answer: () => Response) =>
  server.use(http.post('*/api/orders/:orderId/pay', answer));

describe('Payment step', () => {
  it('Order awaiting payment: the pay-by time, the card form and "Pay" with the total', async () => {
    const order = addOrder();
    await openPayment(order.orderId);
    expect(screen.getByText(formatTime(order.reservationExpiresAt))).toBeInTheDocument();
    expect(screen.getByText(/about 15 minutes left/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Pay UAH 12,999.00' })).toBeInTheDocument();
    expect(screen.getByText(`#${order.orderId.slice(0, 8)}`)).toHaveAttribute(
      'title',
      order.orderId,
    );
    const summary = screen.getByRole('complementary', { name: 'Order summary' });
    expect(within(summary).getByText('Sony WH-1000XM5 headphones × 1')).toBeInTheDocument();
  });

  it('Order already paid: no card form, a link to the order page', async () => {
    const order = addOrder({ status: 'PAID', paidAt: new Date().toISOString() });
    await openPayment(order.orderId);
    expect(screen.getByText('This order is paid.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open the order' })).toHaveAttribute(
      'href',
      `/account/orders/${order.orderId}`,
    );
    expect(screen.queryByLabelText('Card number')).not.toBeInTheDocument();
  });

  it('Unknown order: "Order not found" with a link to the order history', async () => {
    loginAs('CUSTOMER');
    renderApp(`/checkout/${crypto.randomUUID()}`);
    expect(await screen.findByText('Order not found')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Your orders' })).toHaveAttribute(
      'href',
      '/account/orders',
    );
  });
});

describe('Card form', () => {
  it('Expired card: no request, the expiry field says it has expired', async () => {
    const order = addOrder();
    const calls: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.url.endsWith('/pay')) calls.push(request.url);
    });
    await openPayment(order.orderId);
    await fillCard('4242424242424242', '01/20');
    await pay();
    expect(await screen.findByText('This card has expired')).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('Test cards shown', async () => {
    await openPayment(addOrder().orderId);
    const cards = screen.getByRole('group', { name: 'Test cards' });
    for (const text of [
      '… 4242',
      'Approved',
      '… 0002',
      'Declined, insufficient funds',
      '… 5000',
      'Answer lost; pay again with the same card',
      'any other',
    ]) {
      expect(within(cards).getByText(text)).toBeInTheDocument();
    }
  });

  it('keeps no card value in storage or in the query cache', async () => {
    const order = addOrder();
    const { queryClient } = await openPayment(order.orderId);
    await fillCard('4000000000000002');
    await pay();
    await screen.findByRole('alert');
    const stored = JSON.stringify([
      { ...window.localStorage },
      { ...window.sessionStorage },
      queryClient
        .getQueryCache()
        .getAll()
        .map((q) => [q.queryKey, q.state.data]),
    ]);
    expect(stored).not.toContain('4000000000000002');
    expect(stored).not.toContain('"123"');
  });
});

describe('Payment outcome', () => {
  it('Card declined: the reason and the pay-by time, the form stays', async () => {
    const order = addOrder();
    await openPayment(order.orderId);
    await fillCard('4000000000000002');
    await pay();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      `This card was declined: insufficient funds. Try another card; your order stays reserved until ${formatTime(order.reservationExpiresAt)}.`,
    );
    expect(screen.getByLabelText('Card number')).toBeInTheDocument();
  });

  it('Approved payment: the order page with "Payment received", the cart read again', async () => {
    const order = addOrder();
    const cart = recordCartRequests();
    const { router } = await openPayment(order.orderId);
    await fillCard('4242 4242 4242 4242');
    const before = cart.length;
    await pay();
    await waitFor(() => expect(currentUrl(router)).toBe(`/account/orders/${order.orderId}`));
    expect(await screen.findByText('Payment received')).toBeInTheDocument();
    await waitFor(() => expect(cart.slice(before)).toContain('GET /customers/cart'));
  });

  it('Payment in progress: try again with the same card', async () => {
    payAnswer(() => problem(409, 'Payment attempt in progress', {}, 'order-service', 'conflict'));
    await openPayment(addOrder().orderId);
    await fillCard('4242424242424242');
    await pay();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Your previous payment is still being processed. Try again with the same card.',
    );
  });

  it('Payment window elapsed: the form is replaced by a link to the order', async () => {
    payAnswer(() =>
      problem(409, 'Payment window expired', {}, 'order-service', 'payment-window-expired'),
    );
    const order = addOrder();
    await openPayment(order.orderId);
    await fillCard('4242424242424242');
    await pay();
    expect(await screen.findByText('The payment window has passed')).toBeInTheDocument();
    expect(screen.queryByLabelText('Card number')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open the order' })).toBeInTheDocument();
  });

  it('Payment service unavailable: trying again with the same card is safe', async () => {
    payAnswer(() => new HttpResponse(null, { status: 503 }));
    await openPayment(addOrder().orderId);
    await fillCard('4242424242424242');
    await pay();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The payment could not be confirmed. Trying again with the same card will not charge it twice.',
    );
  });

  it('Window passes while the page is open: the expired notice replaces the form', async () => {
    const order = addOrder({ reservationExpiresAt: new Date(Date.now() + 1_500).toISOString() });
    await openPayment(order.orderId);
    expect(screen.getByLabelText('Card number')).toBeInTheDocument();
    expect(
      await screen.findByText('The payment window has passed', {}, { timeout: 3_000 }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Card number')).not.toBeInTheDocument();
  });

  it('Ordered products leave the cart: the badge is hidden after paying for the whole cart', async () => {
    const order = addOrder();
    db.carts.set(order.customerId, [{ productId: order.items[0]!.productId, quantity: 1 }]);
    await openPayment(order.orderId);
    expect(await screen.findByRole('link', { name: 'Cart, 1 item' })).toBeInTheDocument();
    await fillCard('4242424242424242');
    await pay();
    expect(await screen.findByRole('link', { name: 'Cart' })).toBeInTheDocument();
  });
});
