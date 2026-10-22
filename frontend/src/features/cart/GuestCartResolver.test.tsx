import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { StrictMode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { routes } from '../../app/routes';
import { db } from '../../mocks/db';
import { createMockToken } from '../../mocks/tokens';
import { SESSION_STORAGE_KEY } from '../../session/SessionProvider';
import { recordCartRequests } from '../../test/cart';
import { loginAs, renderApp, renderWithProviders } from '../../test/render';
import { server } from '../../test/server';
import { readGuestCart, writeGuestCart } from './guest-cart';

const active = () =>
  db.products
    .filter((p) => p.status === 'ACTIVE' && p.stockStatus === 'IN_STOCK')
    .sort((a, b) => (a.productId < b.productId ? -1 : 1));
const customerId = () => db.users.find((u) => u.role === 'CUSTOMER')!.id;
const line = (index: number, quantity: number) => ({
  productId: active()[index]!.productId,
  quantity,
});

/** A stored session of a new customer with an empty account cart. */
function newCustomerSession() {
  const user = { id: crypto.randomUUID(), email: 'new@example.com', password: 'secret' };
  db.users.push({ ...user, role: 'CUSTOMER' });
  window.localStorage.setItem(SESSION_STORAGE_KEY, createMockToken({ ...user, role: 'CUSTOMER' }));
  return user;
}

const dialog = () => screen.findByRole('dialog', { name: 'You have products in two carts' });

describe('Guest cart on login', () => {
  it('Empty account cart: registering replaces it with the guest cart without asking', async () => {
    const guest = [line(0, 1), line(1, 2)];
    writeGuestCart(guest);
    const calls = recordCartRequests();
    renderApp('/register');
    await userEvent.type(await screen.findByLabelText('Email'), 'fresh@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'secret-1');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'secret-1');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('link', { name: 'Cart, 3 items' })).toBeInTheDocument();
    expect(calls).toContain('PUT /customers/cart');
    const user = db.users.find((u) => u.email === 'fresh@example.com')!;
    expect(db.carts.get(user.id)).toEqual(guest);
    expect(readGuestCart()).toEqual([]);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Empty guest cart: no question, the account cart unchanged', async () => {
    loginAs('CUSTOMER');
    const before = db.carts.get(customerId());
    const calls = recordCartRequests();
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Cart, 4 items' })).toBeInTheDocument();
    expect(calls).toEqual(['GET /customers/cart']);
    expect(db.carts.get(customerId())).toBe(before);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('Customer Service down at login: the guest cart kept, Try again resumes the rule', async () => {
    writeGuestCart([line(0, 1)]);
    server.use(http.get('*/api/customers/cart', () => new HttpResponse(null, { status: 503 })));
    loginAs('CUSTOMER');
    renderApp('/');
    const failed = await screen.findByRole('dialog', { name: 'Your cart could not be loaded' });
    expect(within(failed).getByRole('alert')).toHaveTextContent(
      'This service is temporarily unavailable.',
    );
    expect(readGuestCart()).toHaveLength(1);
    server.resetHandlers();
    await userEvent.click(within(failed).getByRole('button', { name: 'Try again' }));
    expect(await dialog()).toBeInTheDocument();
  });

  it('does not start for a session that began in another tab', async () => {
    const guest = [line(0, 1)];
    writeGuestCart(guest);
    renderApp('/');
    await screen.findByRole('link', { name: 'Cart, 1 item' });
    const calls = recordCartRequests();
    act(() => {
      loginAs('CUSTOMER');
      window.dispatchEvent(new StorageEvent('storage', { key: SESSION_STORAGE_KEY }));
    });
    expect(await screen.findByRole('link', { name: 'Cart, 4 items' })).toBeInTheDocument();
    expect(calls).toEqual(['GET /customers/cart']);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(readGuestCart()).toEqual(guest);
  });

  it('sends a single PUT under StrictMode', async () => {
    const user = newCustomerSession();
    writeGuestCart([line(0, 2)]);
    const calls = recordCartRequests();
    renderWithProviders(
      <StrictMode>
        <RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />
      </StrictMode>,
    );
    await waitFor(() => expect(db.carts.get(user.id)).toEqual([line(0, 2)]));
    await waitFor(() => expect(readGuestCart()).toEqual([]));
    expect(calls.filter((call) => call === 'PUT /customers/cart')).toHaveLength(1);
  });
});

describe('Replace, keep or merge choice', () => {
  function bothCarts(account: { productId: string; quantity: number }[]) {
    db.carts.set(customerId(), account);
    loginAs('CUSTOMER');
  }

  it('Merge: 1 + 2 of A and 1 of B give 3 of A and 1 of B; logout leaves an empty cart', async () => {
    bothCarts([line(0, 1)]);
    writeGuestCart([line(0, 2), line(1, 1)]);
    const calls = recordCartRequests();
    renderApp('/');
    const choice = await dialog();
    await waitFor(() =>
      expect(within(choice).getByRole('group', { name: 'In this browser' })).toHaveTextContent(
        `2 × ${active()[0]!.title}1 × ${active()[1]!.title}`,
      ),
    );
    expect(within(choice).getByRole('group', { name: 'In your account' })).toHaveTextContent(
      `1 × ${active()[0]!.title}`,
    );
    await userEvent.click(within(choice).getByRole('button', { name: 'Merge both' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls).toContain('POST /customers/cart/merge');
    expect(db.carts.get(customerId())).toEqual([line(0, 3), line(1, 1)]);
    expect(screen.getByRole('link', { name: 'Cart, 4 items' })).toBeInTheDocument();
    expect(readGuestCart()).toEqual([]);

    // Spec shopping-cart, Logout after merge: the visitor's cart is empty.
    await userEvent.click(screen.getByRole('button', { name: 'Log out' }));
    expect(await screen.findByRole('link', { name: 'Cart' })).toBeInTheDocument();
  });

  it('Keep account cart: no cart change is sent and the account cart is shown', async () => {
    bothCarts([line(0, 1)]);
    writeGuestCart([line(1, 2)]);
    const calls = recordCartRequests();
    renderApp('/');
    await userEvent.click(
      within(await dialog()).getByRole('button', { name: 'Keep account cart' }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls).toEqual(['GET /customers/cart', 'GET /customers/cart']);
    expect(db.carts.get(customerId())).toEqual([line(0, 1)]);
    expect(readGuestCart()).toEqual([]);
  });

  it('Replace: PUT with the guest lines', async () => {
    bothCarts([line(0, 1)]);
    writeGuestCart([line(1, 2)]);
    renderApp('/');
    await userEvent.click(
      within(await dialog()).getByRole('button', { name: "Replace with this browser's cart" }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(db.carts.get(customerId())).toEqual([line(1, 2)]);
  });

  it('Merge breaks a limit: the dialog stays with the message; the other choices still work', async () => {
    bothCarts([line(0, 9)]);
    writeGuestCart([line(0, 2)]);
    renderApp('/');
    const choice = await dialog();
    await userEvent.keyboard('{Escape}');
    await userEvent.click(within(choice).getByRole('button', { name: 'Merge both' }));
    expect(await within(choice).findByRole('alert')).toHaveTextContent(
      'You can have up to 10 of each product in your cart.',
    );
    expect(screen.getByRole('dialog', { name: 'You have products in two carts' })).toBeVisible();
    expect(readGuestCart()).toEqual([line(0, 2)]);
    await userEvent.click(within(choice).getByRole('button', { name: 'Keep account cart' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('reads the account cart again after a choice fails with 503', async () => {
    bothCarts([line(0, 1)]);
    writeGuestCart([line(1, 1)]);
    renderApp('/');
    const choice = await dialog();
    server.use(
      http.post('*/api/customers/cart/merge', () => new HttpResponse(null, { status: 503 })),
    );
    const calls = recordCartRequests();
    await userEvent.click(within(choice).getByRole('button', { name: 'Merge both' }));
    expect(await within(await dialog()).findByRole('alert')).toHaveTextContent(
      'temporarily unavailable',
    );
    expect(calls).toEqual(['POST /customers/cart/merge', 'GET /customers/cart']);
  });
});
