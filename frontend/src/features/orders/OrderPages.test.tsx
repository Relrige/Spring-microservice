import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { formatDateTime, formatTime } from '../../components/DateTime';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { addOrder } from '../../test/orders';
import { loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

afterEach(() => vi.useRealTimers());

function countOrderReads(orderId: string): { count: number } {
  const reads = { count: 0 };
  server.events.on('request:start', ({ request }) => {
    if (request.method === 'GET' && request.url.endsWith(`/orders/${orderId}`)) reads.count += 1;
  });
  return reads;
}

async function openOrder(orderId: string) {
  loginAs('CUSTOMER');
  const rendered = renderApp(`/account/orders/${orderId}`);
  await screen.findByRole('heading', { level: 1, name: /^Order #/ });
  return rendered;
}

const paidNow = () => ({ status: 'PAID' as const, paidAt: new Date().toISOString() });

describe('Order history', () => {
  it('Orders listed: newest first with short id, date, total and status', async () => {
    const now = Date.now();
    const old = addOrder({
      placedAt: new Date(now - 3 * 86_400_000).toISOString(),
      status: 'EXPIRED',
    });
    const middle = addOrder({
      placedAt: new Date(now - 86_400_000).toISOString(),
      status: 'DELIVERED',
    });
    const newest = addOrder({ placedAt: new Date(now).toISOString() });
    loginAs('CUSTOMER');
    renderApp('/account/orders');
    const rows = await screen.findAllByTestId('order-row');
    expect(rows).toHaveLength(3);
    expect(rows.map((row) => row.textContent)).toEqual(
      [newest, middle, old].map((order) =>
        expect.stringContaining(`#${order.orderId.slice(0, 8)}`),
      ),
    );
    expect(rows[0]).toHaveTextContent(formatDateTime(newest.placedAt));
    expect(rows[0]).toHaveTextContent('UAH 12,999.00');
    expect(rows[0]).toHaveTextContent('Awaiting payment');
    expect(within(rows[1]!).getByRole('link')).toHaveAttribute(
      'href',
      `/account/orders/${middle.orderId}`,
    );
  });

  it('No orders: "You have no orders yet" and a link to the catalog', async () => {
    loginAs('CUSTOMER');
    renderApp('/account/orders');
    expect(await screen.findByText('You have no orders yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse the catalog' })).toHaveAttribute('href', '/');
  });

  it('Status chip: PENDING_SHIPPING reads "Ready to ship"', async () => {
    addOrder({ ...paidNow(), status: 'PENDING_SHIPPING', ttn: '20450000000001' });
    loginAs('CUSTOMER');
    renderApp('/account/orders');
    expect(await screen.findByText('Ready to ship')).toBeInTheDocument();
  });
});

describe('Order details', () => {
  it('Paid order with a TTN: items, total, "Ready to ship" and the TTN', async () => {
    const order = addOrder({
      ...paidNow(),
      status: 'PENDING_SHIPPING',
      ttn: '20450000000001',
      deliveryStatus: 'TTN_GENERATED',
    });
    await openOrder(order.orderId);
    expect(screen.getAllByText('Ready to ship').length).toBeGreaterThan(0);
    expect(screen.getByText('20450000000001')).toBeInTheDocument();
    expect(screen.getByText('TTN ready')).toBeInTheDocument();
    expect(screen.getByText('Sony WH-1000XM5 headphones')).toBeInTheDocument();
    expect(screen.getByTestId('order-total')).toHaveTextContent('UAH 12,999.00');
  });

  it('Order of another customer or unknown: "Order not found" with a link to the history', async () => {
    const stranger = db.users.find((u) => u.role === 'ADMIN')!.id;
    const order = addOrder({ customerId: stranger });
    loginAs('CUSTOMER');
    renderApp(`/account/orders/${order.orderId}`);
    expect(await screen.findByRole('heading', { name: 'Order not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Your orders' })).toHaveAttribute(
      'href',
      '/account/orders',
    );
  });

  it('Delivery data missing: tracking appears once the parcel is prepared, no error', async () => {
    const order = addOrder(paidNow());
    await openOrder(order.orderId);
    expect(screen.getByText('Tracking appears once the parcel is prepared.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('Unpaid order: the pay-by time and "Pay now" to its payment step', async () => {
    const order = addOrder();
    await openOrder(order.orderId);
    expect(screen.getByText(formatTime(order.reservationExpiresAt))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Pay now' })).toHaveAttribute(
      'href',
      `/checkout/${order.orderId}`,
    );
  });
});

describe('Order kept up to date', () => {
  it('TTN arrives: the page shows "Ready to ship" and the TTN without a reload', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const order = addOrder(paidNow());
    await openOrder(order.orderId);
    expect(screen.getAllByText('Paid').length).toBeGreaterThan(0);
    await act(() => vi.advanceTimersByTimeAsync(5_500));
    expect(await screen.findByText('TTN ready')).toBeInTheDocument();
    expect(screen.getAllByText('Ready to ship').length).toBeGreaterThan(0);
  });

  it('Final status: no further order requests while the page stays open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const order = addOrder({ ...paidNow(), status: 'DELIVERED', deliveryStatus: 'DELIVERED' });
    const reads = countOrderReads(order.orderId);
    await openOrder(order.orderId);
    const before = reads.count;
    await act(() => vi.advanceTimersByTimeAsync(12_000));
    expect(reads.count).toBe(before);
  });
});

describe('Cancel an order', () => {
  async function cancel() {
    await userEvent.click(screen.getByRole('button', { name: 'Cancel order' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this order?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel order' }));
  }

  it('Cancel an unpaid order: DELETE and the order shows "Cancelled"', async () => {
    const order = addOrder();
    const deletes: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (request.method === 'DELETE') deletes.push(new URL(request.url).pathname);
    });
    await openOrder(order.orderId);
    await cancel();
    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
    expect(deletes).toEqual([`/api/orders/${order.orderId}`]);
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });

  it('Cancellation still running: "Cancelling" with the note, read again until cancelled', async () => {
    const order = addOrder(paidNow());
    server.use(
      http.delete('*/api/orders/:orderId', () => {
        order.status = 'CANCELLATION_REQUESTED';
        return HttpResponse.json({ orderId: order.orderId, status: order.status }, { status: 202 });
      }),
    );
    await openOrder(order.orderId);
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await cancel();
    expect(await screen.findByText('Cancelling')).toBeInTheDocument();
    expect(screen.getByText(/refund and the stock release finish shortly/)).toBeInTheDocument();
    order.status = 'CANCELLED';
    await act(() => vi.advanceTimersByTimeAsync(5_500));
    await waitFor(() => expect(screen.getByText('Cancelled')).toBeInTheDocument());
  });

  it('Cancel not possible any more: the message, and the order shows as shipped', async () => {
    const order = addOrder(paidNow());
    server.use(
      http.delete('*/api/orders/:orderId', () => {
        order.status = 'SHIPPED';
        return problem(
          409,
          'Order cannot be cancelled',
          { orderStatus: 'SHIPPED' },
          'order-service',
          'conflict',
        );
      }),
    );
    await openOrder(order.orderId);
    await cancel();
    expect(await screen.findByText('Shipped')).toBeInTheDocument();
    expect(screen.getByText('This order can no longer be cancelled.')).toBeInTheDocument();
  });

  it('Shipped order: no "Cancel order" control', async () => {
    const order = addOrder({ ...paidNow(), status: 'SHIPPED', ttn: '20450000000001' });
    await openOrder(order.orderId);
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });

  it('cancels from the payment step and opens the order page', async () => {
    const order = addOrder();
    loginAs('CUSTOMER');
    renderApp(`/checkout/${order.orderId}`);
    await screen.findByRole('button', { name: 'Cancel order' });
    await cancel();
    expect(await screen.findByRole('heading', { level: 1, name: /^Order #/ })).toBeInTheDocument();
    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
  });
});
