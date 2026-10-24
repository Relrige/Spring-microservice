import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DEMO_CUSTOMER, DEMO_STAFF } from '../fixtures/demo-data';
import { currentUrl, loginAs, renderApp } from '../test/render';
import { currentUserText } from '../test/ui';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { db } from '../mocks/db';
import { createMockToken } from '../mocks/tokens';
import { renderWithProviders } from '../test/render';
import { SESSION_STORAGE_KEY, SessionProvider, useSession } from './SessionProvider';

const manager = DEMO_STAFF.find((a) => a.role === 'CATALOG_MANAGER')!;

describe('Session survives reload', () => {
  it('Reload keeps the session: a stored valid token starts the session', async () => {
    loginAs('CUSTOMER');
    renderApp('/');
    await waitFor(() => expect(currentUserText()).toBe(`${DEMO_CUSTOMER.email} · Customer`));
  });

  it('Logout in another tab: this tab shows the logged-out state without a reload', async () => {
    loginAs('CUSTOMER');
    renderApp('/');
    await waitFor(() => expect(currentUserText()).not.toBeNull());
    act(() => {
      window.localStorage.removeItem(SESSION_STORAGE_KEY);
      window.dispatchEvent(new StorageEvent('storage', { key: SESSION_STORAGE_KEY }));
    });
    expect(await screen.findByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(currentUserText()).toBeNull();
  });
});

describe('Session expiry', () => {
  it('Expired token on load: discarded, the user is anonymous', async () => {
    loginAs('CUSTOMER', { lifetimeSeconds: -10 });
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(window.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
  });

  it('Expiry while idle on a protected page: login page with notice and return page', async () => {
    loginAs('CATALOG_MANAGER', { lifetimeSeconds: 2 });
    const { router } = renderApp('/manage');
    expect(await screen.findByRole('heading', { name: 'Products' })).toBeInTheDocument();

    expect(
      await screen.findByText(/Your session has expired/, {}, { timeout: 4000 }),
    ).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/login?expired=1&returnTo=%2Fmanage%2Fproducts');
    expect(window.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();

    await userEvent.type(screen.getByLabelText('Email'), manager.email);
    await userEvent.type(screen.getByLabelText('Password'), manager.password);
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/manage/products'));
  });

  it('Expiry while idle on the storefront: the user stays and becomes a visitor', async () => {
    loginAs('CUSTOMER', { lifetimeSeconds: 2 });
    const { router } = renderApp('/');
    await waitFor(() => expect(currentUserText()).not.toBeNull());
    expect(
      await screen.findByRole('link', { name: 'Log in' }, { timeout: 4000 }),
    ).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/');
  });
});

describe('Logout', () => {
  it('ends the session, removes the token and lands on the storefront as a visitor', async () => {
    loginAs('CATALOG_MANAGER');
    const { router, queryClient } = renderApp('/manage');
    queryClient.setQueryData(['private'], 'manager data');
    await userEvent.click(await screen.findByRole('button', { name: 'Log out' }));
    await waitFor(() => expect(currentUrl(router)).toBe('/'));
    expect(window.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(queryClient.getQueryData(['private'])).toBeUndefined();
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument();
  });
});

describe('Session source', () => {
  let current: ReturnType<typeof useSession>;
  function Probe() {
    current = useSession();
    return null;
  }
  const renderProbe = () =>
    renderWithProviders(
      <RouterProvider
        router={createMemoryRouter([
          {
            path: '*',
            element: (
              <SessionProvider>
                <Probe />
              </SessionProvider>
            ),
          },
        ])}
      />,
    );

  it('reports stored on reload, login after a login in this tab, other-tab after a login elsewhere', async () => {
    loginAs('CUSTOMER');
    renderProbe();
    expect(current.source).toBe('stored');

    const worker = db.users.find((u) => u.role === 'INVENTORY_WORKER')!;
    act(() => {
      window.localStorage.setItem(SESSION_STORAGE_KEY, createMockToken(worker));
      window.dispatchEvent(new StorageEvent('storage', { key: SESSION_STORAGE_KEY }));
    });
    await waitFor(() => expect(current.session?.role).toBe('INVENTORY_WORKER'));
    expect(current.source).toBe('other-tab');

    act(() => {
      current.startSession(createMockToken(db.users.find((u) => u.role === 'CUSTOMER')!));
    });
    expect(current.source).toBe('login');
    expect(current.session?.role).toBe('CUSTOMER');
  });
});
