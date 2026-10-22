import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { addOrder, ORDER_PRODUCT } from '../../test/orders';
import { loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

afterEach(() => vi.useRealTimers());

const product = () => db.products.find((p) => p.title === ORDER_PRODUCT)!;
const paidAgo = (ms: number) => ({
  status: 'PAID' as const,
  paidAt: new Date(Date.now() - ms).toISOString(),
});

async function openQueue() {
  loginAs('INVENTORY_WORKER');
  renderApp('/warehouse/queue');
  await screen.findByRole('heading', { level: 1, name: 'Fulfillment queue' });
}

const cardOf = async (orderId: string) =>
  (await screen.findByText(`#${orderId.slice(0, 8)}`)).closest<HTMLElement>(
    '[data-testid="queue-order"]',
  )!;

describe('Fulfillment queue', () => {
  it('Orders waiting: oldest payment first, with items and delivery details', async () => {
    const newer = addOrder(paidAgo(5_000));
    const older = addOrder(paidAgo(60_000));
    await openQueue();
    const cards = await screen.findAllByTestId('queue-order');
    expect(cards.map((c) => c.textContent)).toEqual([
      expect.stringContaining(`#${older.orderId.slice(0, 8)}`),
      expect.stringContaining(`#${newer.orderId.slice(0, 8)}`),
    ]);
    expect(cards[0]).toHaveTextContent(`${ORDER_PRODUCT} × 1`);
    expect(cards[0]).toHaveTextContent(
      'Olena Kovalenko, +380501234567 · Nova Poshta: Kyiv, branch No. 12',
    );
    expect(within(cards[0]!).getByText('TTN ready')).toBeInTheDocument();
  });

  it('Empty queue: no orders are waiting', async () => {
    await openQueue();
    expect(await screen.findByText('No orders are waiting for dispatch')).toBeInTheDocument();
  });

  it('Delivery Service down: the notice, and no Mark as shipped', async () => {
    const order = addOrder(paidAgo(60_000));
    server.use(
      http.get('*/api/orders/fulfillment-queue', () =>
        HttpResponse.json({
          deliveryDataAvailable: false,
          orders: [
            {
              orderId: order.orderId,
              status: 'PAID',
              placedAt: order.placedAt,
              paidAt: order.paidAt!,
              items: [{ productId: product().productId, productTitle: ORDER_PRODUCT, quantity: 1 }],
              deliveryDetails: order.deliveryDetails,
            },
          ],
        }),
      ),
    );
    await openQueue();
    expect(
      await screen.findByText(/Delivery statuses cannot be loaded right now/),
    ).toBeInTheDocument();
    expect(await cardOf(order.orderId)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Mark as shipped' })).not.toBeInTheDocument();
  });

  it('TTN arrives: within about 5 seconds the order shows TTN ready and Mark as shipped', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const order = addOrder(paidAgo(0));
    await openQueue();
    const card = await cardOf(order.orderId);
    expect(within(card).queryByRole('button', { name: 'Mark as shipped' })).not.toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(5_500));
    expect(await within(await cardOf(order.orderId)).findByText('TTN ready')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark as shipped' })).toBeInTheDocument();
  });
});

describe('Mark as shipped', () => {
  it('Order shipped: confirm, POST ship, "Order shipped" and the order leaves the queue', async () => {
    const order = addOrder(paidAgo(60_000));
    await openQueue();
    await userEvent.click(
      within(await cardOf(order.orderId)).getByRole('button', { name: 'Mark as shipped' }),
    );
    const dialog = await screen.findByRole('dialog', { name: 'Mark as shipped?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Mark as shipped' }));
    expect(await screen.findByText('Order shipped')).toBeInTheDocument();
    expect(await screen.findByText('No orders are waiting for dispatch')).toBeInTheDocument();
    expect(order.status).toBe('SHIPPED');
  });

  it('TTN not ready: TTN pending and no Mark as shipped', async () => {
    const order = addOrder({ ...paidAgo(60_000), deliveryStatus: 'TTN_PENDING' });
    await openQueue();
    const card = await cardOf(order.orderId);
    expect(within(card).getByText('TTN pending')).toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Mark as shipped' })).not.toBeInTheDocument();
  });

  it('Shipped elsewhere: 409 says the order changed and reads the queue again', async () => {
    const order = addOrder(paidAgo(60_000));
    server.use(
      http.post('*/api/orders/:id/ship', () => {
        order.status = 'SHIPPED';
        return problem(
          409,
          'Order is not awaiting dispatch',
          { orderStatus: 'SHIPPED' },
          'order-service',
          'conflict',
        );
      }),
    );
    await openQueue();
    await userEvent.click(
      within(await cardOf(order.orderId)).getByRole('button', { name: 'Mark as shipped' }),
    );
    await userEvent.click(
      within(await screen.findByRole('dialog', { name: 'Mark as shipped?' })).getByRole('button', {
        name: 'Mark as shipped',
      }),
    );
    expect(await screen.findByText('No orders are waiting for dispatch')).toBeInTheDocument();
  });
});

describe('Retry a failed TTN', () => {
  it('Retry succeeds: the new TTN and Mark as shipped', async () => {
    product().packaging = null;
    const order = addOrder(paidAgo(60_000));
    await openQueue();
    const card = await cardOf(order.orderId);
    expect(within(card).getByText('TTN failed')).toBeInTheDocument();
    product().packaging = { grossWeightGrams: 350, lengthCm: 26, widthCm: 21, heightCm: 10 };
    await userEvent.click(within(card).getByRole('button', { name: 'Retry TTN' }));
    await waitFor(() => expect(within(card).getByText('TTN ready')).toBeInTheDocument());
    expect(within(card).getByRole('button', { name: 'Mark as shipped' })).toBeInTheDocument();
    expect(within(card).getByText(/^2045\d{10}$/)).toBeInTheDocument();
  });

  it('Packaging still missing: names the product and links to its stock row', async () => {
    product().packaging = null;
    const order = addOrder(paidAgo(60_000));
    await openQueue();
    const card = await cardOf(order.orderId);
    await userEvent.click(within(card).getByRole('button', { name: 'Retry TTN' }));
    const alert = await within(card).findByRole('alert');
    expect(alert).toHaveTextContent(`${ORDER_PRODUCT} has no packaging data.`);
    expect(within(alert).getByRole('link', { name: 'Set packaging' })).toHaveAttribute(
      'href',
      `/warehouse?q=${encodeURIComponent(ORDER_PRODUCT)}`,
    );
  });
});
