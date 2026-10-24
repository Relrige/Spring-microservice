import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { DEMO_PROFILE } from '../../fixtures/demo-data';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

const customerId = () => db.users.find((u) => u.role === 'CUSTOMER')!.id;
const product = (title: string) => db.products.find((p) => p.title === title)!;
const SONY = 'Sony WH-1000XM5 headphones';
const ANKER = 'Anker 737 power bank, 24,000 mAh';

/** The demo customer with a cart of two available products. */
function customerWithCart() {
  db.carts.set(
    customerId(),
    [
      { productId: product(SONY).productId, quantity: 1 },
      { productId: product(ANKER).productId, quantity: 2 },
    ].sort((a, b) => (a.productId < b.productId ? -1 : 1)),
  );
  loginAs('CUSTOMER');
}

interface Sent {
  method: string;
  path: string;
  key: string | null;
  body: unknown;
}

function recordRequests(): Sent[] {
  const sent: Sent[] = [];
  server.events.on('request:start', async ({ request }) => {
    const path = new URL(request.url).pathname.replace(/^\/api/, '');
    if (!path.startsWith('/orders') && !path.startsWith('/customers/me')) return;
    const text = await request.clone().text();
    sent.push({
      method: request.method,
      path,
      key: request.headers.get('Idempotency-Key'),
      body: text ? JSON.parse(text) : undefined,
    });
  });
  return sent;
}
const placements = (sent: Sent[]) =>
  sent.filter((s) => s.method === 'POST' && s.path === '/orders');

async function openCheckout() {
  const rendered = renderApp('/checkout');
  await screen.findByRole('button', { name: 'Place order' });
  await screen.findByText(`${SONY} × 1`);
  return rendered;
}

const placeOrder = () => userEvent.click(screen.getByRole('button', { name: 'Place order' }));

describe('Delivery details form', () => {
  it('Pre-filled checkout: recipient, phone and address from the profile', async () => {
    customerWithCart();
    await openCheckout();
    await waitFor(() =>
      expect(screen.getByLabelText('Recipient')).toHaveValue(
        `${DEMO_PROFILE.firstName} ${DEMO_PROFILE.lastName}`,
      ),
    );
    expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone);
    expect(screen.getByLabelText(/^Address/)).toHaveValue(DEMO_PROFILE.defaultAddress);
    expect(screen.getByLabelText('Save these details to my profile')).not.toBeChecked();
    expect(screen.getByTestId('checkout-total')).toHaveTextContent('UAH 22,797.00');
  });

  it('Profile unavailable: the form is empty and can be filled in', async () => {
    customerWithCart();
    server.use(
      http.get('*/api/customers/me/profile', () => new HttpResponse(null, { status: 503 })),
    );
    await openCheckout();
    expect(screen.getByLabelText('Recipient')).toHaveValue('');
    await userEvent.type(screen.getByLabelText('Recipient'), 'Ivan Petrenko');
    expect(screen.getByLabelText('Recipient')).toHaveValue('Ivan Petrenko');
  });

  it('Missing recipient: no request, the field says it is required', async () => {
    customerWithCart();
    const sent = recordRequests();
    await openCheckout();
    await waitFor(() => expect(screen.getByLabelText('Recipient')).not.toHaveValue(''));
    await userEvent.clear(screen.getByLabelText('Recipient'));
    await placeOrder();
    expect(await screen.findByText('Enter the recipient')).toBeInTheDocument();
    expect(placements(sent)).toEqual([]);
  });

  it('Carrier choice: Nova Poshta and no other carrier', async () => {
    customerWithCart();
    await openCheckout();
    const carrier = screen.getByRole('combobox', { name: 'Carrier' });
    expect(carrier).toHaveValue('Nova Poshta');
    await userEvent.click(carrier);
    const options = await screen.findAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual(['Nova Poshta']);
  });

  it('Empty cart: "Your cart is empty" with a link to the catalog and no order request', async () => {
    db.carts.set(customerId(), []);
    loginAs('CUSTOMER');
    const sent = recordRequests();
    renderApp('/checkout');
    expect(await screen.findByText('Your cart is empty')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse the catalog' })).toHaveAttribute('href', '/');
    expect(sent.filter((s) => s.path.startsWith('/orders'))).toEqual([]);
  });
});

describe('Save delivery details to the profile', () => {
  it('Details saved with the order: the recipient split into first and last name', async () => {
    db.profiles.delete(customerId());
    customerWithCart();
    const sent = recordRequests();
    const { router } = await openCheckout();
    expect(screen.getByLabelText('Save these details to my profile')).toBeChecked();
    await userEvent.type(screen.getByLabelText('Recipient'), 'Olena Kovalenko');
    await userEvent.type(screen.getByLabelText('Phone'), '+380 50 123 45 67');
    await userEvent.type(screen.getByLabelText(/^Address/), 'Kyiv, branch 12');
    await placeOrder();
    await waitFor(() => expect(currentUrl(router)).toMatch(/^\/checkout\/[0-9a-f-]{36}$/));
    await waitFor(() =>
      expect(sent.find((s) => s.method === 'PUT')?.body).toEqual({
        firstName: 'Olena',
        lastName: 'Kovalenko',
        phone: '+380501234567',
        defaultAddress: 'Kyiv, branch 12',
      }),
    );
  });

  it('Profile save fails: the payment step still opens', async () => {
    db.profiles.delete(customerId());
    customerWithCart();
    server.use(
      http.put('*/api/customers/me/profile', () => new HttpResponse(null, { status: 503 })),
    );
    const { router } = await openCheckout();
    await userEvent.type(screen.getByLabelText('Recipient'), 'Olena Kovalenko');
    await userEvent.type(screen.getByLabelText('Phone'), '+380501234567');
    await userEvent.type(screen.getByLabelText(/^Address/), 'Kyiv');
    await placeOrder();
    await waitFor(() => expect(currentUrl(router)).toMatch(/^\/checkout\/[0-9a-f-]{36}$/));
  });
});

describe('Order submission', () => {
  it('Order placed: both lines and the delivery details, then the payment step', async () => {
    customerWithCart();
    const sent = recordRequests();
    const { router } = await openCheckout();
    await waitFor(() => expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone));
    await placeOrder();
    await waitFor(() => expect(currentUrl(router)).toMatch(/^\/checkout\/[0-9a-f-]{36}$/));
    const [placed] = placements(sent);
    expect(placed!.body).toMatchObject({
      items: expect.arrayContaining([
        { productId: product(SONY).productId, quantity: 1 },
        { productId: product(ANKER).productId, quantity: 2 },
      ]),
      deliveryDetails: {
        name: 'Olena Kovalenko',
        phone: DEMO_PROFILE.phone,
        address: DEMO_PROFILE.defaultAddress,
        carrier: 'NOVA_POSHTA',
      },
    });
    expect(placed!.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(currentUrl(router)).toBe(`/checkout/${db.orders[0]!.orderId}`);
  });

  it('Retry after an unavailable service: the same Idempotency-Key', async () => {
    customerWithCart();
    let calls = 0;
    server.use(
      http.post('*/api/orders', () =>
        ++calls === 1 ? new HttpResponse(null, { status: 503 }) : undefined,
      ),
    );
    const sent = recordRequests();
    const { router } = await openCheckout();
    await waitFor(() => expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone));
    await placeOrder();
    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable');
    await placeOrder();
    await waitFor(() => expect(currentUrl(router)).toMatch(/^\/checkout\/[0-9a-f-]{36}$/));
    const [first, second] = placements(sent);
    expect(second!.key).toBe(first!.key);
  });

  it('Details changed before a retry: a new Idempotency-Key', async () => {
    customerWithCart();
    server.use(http.post('*/api/orders', () => new HttpResponse(null, { status: 503 })));
    const sent = recordRequests();
    await openCheckout();
    await waitFor(() => expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone));
    await placeOrder();
    await screen.findByRole('alert');
    await userEvent.type(screen.getByLabelText(/^Address/), ', entrance 2');
    await placeOrder();
    await waitFor(() => expect(placements(sent)).toHaveLength(2));
    const [first, second] = placements(sent);
    expect(second!.key).not.toBe(first!.key);
  });

  it('Replayed answer: the payment step of the first order opens', async () => {
    customerWithCart();
    const { router } = await openCheckout();
    await waitFor(() => expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone));
    // The first request reached the server, but its answer was lost.
    let lost = true;
    server.use(
      http.post('*/api/orders', async ({ request }) => {
        if (!lost) return undefined;
        lost = false;
        const { handlers } = await import('../../mocks/handlers');
        const place = handlers.find((h) => h.info.header === 'POST */api/orders')!;
        await place.run({ request: request.clone(), requestId: 'lost' });
        return HttpResponse.error();
      }),
    );
    await placeOrder();
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach VoltStore');
    await placeOrder();
    await waitFor(() => expect(currentUrl(router)).toMatch(/^\/checkout\//));
    expect(db.orders).toHaveLength(1);
    expect(currentUrl(router)).toBe(`/checkout/${db.orders[0]!.orderId}`);
  });
});

describe('Submission problems', () => {
  async function placeWith(answer: () => Response) {
    customerWithCart();
    server.use(http.post('*/api/orders', answer));
    const rendered = await openCheckout();
    await waitFor(() => expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone));
    await placeOrder();
    return rendered;
  }

  it('Not enough stock: requested and available per product, values kept', async () => {
    const { router } = await placeWith(() =>
      problem(
        422,
        'Not enough stock',
        {
          insufficientItems: [{ productId: product(ANKER).productId, requested: 3, available: 1 }],
        },
        'order-service',
        'insufficient-stock',
      ),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      `Not enough stock for ${ANKER}: requested 3, available 1.`,
    );
    expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone);
    expect(currentUrl(router)).toBe('/checkout');
  });

  it('Product no longer available: named, with a link back to the cart', async () => {
    await placeWith(() =>
      problem(
        422,
        'Some items cannot be ordered',
        { ineligibleItems: [{ productId: product(SONY).productId, reason: 'NOT_ACTIVE' }] },
        'order-service',
        'ineligible-items',
      ),
    );
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(`${SONY} is no longer available.`);
    expect(within(alert).getByRole('link', { name: 'Back to the cart' })).toHaveAttribute(
      'href',
      '/cart',
    );
  });

  it('Order total too large: the maximum total', async () => {
    await placeWith(() =>
      problem(
        422,
        'Too large',
        { maxTotal: 9999999999.99 },
        'order-service',
        'order-total-too-large',
      ),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The order exceeds the maximum total of UAH 9,999,999,999.99.',
    );
  });

  it('Key used for other details: asks to place again, and the next submission has a new key', async () => {
    const sent = recordRequests();
    let calls = 0;
    await placeWith(() =>
      ++calls === 1
        ? problem(422, 'Key reused', {}, 'order-service', 'idempotency-key-mismatch')
        : new HttpResponse(null, { status: 503 }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('Place the order again.');
    await placeOrder();
    await waitFor(() => expect(placements(sent)).toHaveLength(2));
    const [first, second] = placements(sent);
    expect(second!.key).not.toBe(first!.key);
  });

  it('Service unavailable at submission: the message and nothing sent again', async () => {
    const sent = recordRequests();
    await placeWith(() => new HttpResponse(null, { status: 503 }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This service is temporarily unavailable.',
    );
    expect(placements(sent)).toHaveLength(1);
  });

  it('Submission still running: the same request once more after Retry-After', async () => {
    const sent = recordRequests();
    let calls = 0;
    const { router } = await placeWith(() =>
      ++calls === 1
        ? problem(409, 'Still running', {}, 'order-service', 'request-in-progress', {
            'Retry-After': '1',
          })
        : (undefined as unknown as Response),
    );
    await waitFor(() => expect(currentUrl(router)).toMatch(/^\/checkout\/[0-9a-f-]{36}$/), {
      timeout: 3000,
    });
    const [first, second] = placements(sent);
    expect(second!.key).toBe(first!.key);
  });
});
