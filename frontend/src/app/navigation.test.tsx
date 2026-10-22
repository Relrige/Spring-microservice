import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DEMO_CUSTOMER, DEMO_STAFF } from '../fixtures/demo-data';
import { recordCartRequests } from '../test/cart';
import { currentUrl, loginAs, renderApp } from '../test/render';
import { currentUserText, submitLogin } from '../test/ui';

const manager = DEMO_STAFF.find((a) => a.role === 'CATALOG_MANAGER')!;

describe('Protected pages need a login', () => {
  it('Anonymous visitor opens a protected page: login, then back to it', async () => {
    const { router } = renderApp('/account/orders');
    await waitFor(() => expect(currentUrl(router)).toBe('/login?returnTo=%2Faccount%2Forders'));
    await submitLogin(DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/account/orders'));
    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });

  it('Return page not allowed for the role: the customer lands on the storefront', async () => {
    const { router } = renderApp('/login?returnTo=%2Fwarehouse');
    await submitLogin(DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/'));
    expect(screen.queryByText('You do not have access to this page')).not.toBeInTheDocument();
  });

  it('Wrong role: a customer opening /admin sees access denied with a link to the storefront', async () => {
    loginAs('CUSTOMER');
    renderApp('/admin');
    expect(await screen.findByText('You do not have access to this page')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to the catalog' })).toHaveAttribute('href', '/');
  });

  it('Admin cannot open the manager area', async () => {
    loginAs('ADMIN');
    renderApp('/manage');
    expect(await screen.findByText('You do not have access to this page')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the admin area' })).toHaveAttribute(
      'href',
      '/admin',
    );
  });

  it('shows the placeholder landing pages of the staff areas', async () => {
    loginAs('INVENTORY_WORKER');
    renderApp('/warehouse');
    expect(await screen.findByRole('heading', { name: 'Warehouse' })).toBeInTheDocument();
    expect(screen.getByText(/iteration 5/)).toBeInTheDocument();
  });
});

describe('Not-found page', () => {
  it('Unknown URL: "Page not found" with a link to the catalog, header kept', async () => {
    renderApp('/no-such-page');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the catalog' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'VoltStore home' })).toBeInTheDocument();
  });
});

describe('Role-aware navigation', () => {
  it('Visitor header: catalog, Log in and Register, no protected areas', async () => {
    renderApp('/');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Catalog' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Register' })).toBeInTheDocument();
    for (const name of ['Catalog console', 'Warehouse', 'Admin']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument();
    }
  });

  it('Manager header: manager area, email and role, Log out; no other areas', async () => {
    loginAs('CATALOG_MANAGER');
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Catalog console' })).toHaveAttribute(
      'href',
      '/manage',
    );
    expect(currentUserText()).toBe(`${manager.email} · Catalog manager`);
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument();
    for (const name of ['Warehouse', 'Admin', 'Log in', 'Register']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument();
    }
  });

  it('Phone width: the navigation is reachable through a menu button', async () => {
    window.innerWidth = 375;
    renderApp('/');
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Open menu' }));
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Catalog' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Log in' })).toBeInTheDocument();
  });
});

describe('Cart area', () => {
  it('Manager opens the cart: access denied and no cart request', async () => {
    loginAs('CATALOG_MANAGER');
    const calls = recordCartRequests();
    renderApp('/cart');
    expect(await screen.findByText('You do not have access to this page')).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('Visitor opens the cart: the cart page without a login', async () => {
    const { router } = renderApp('/cart');
    expect(await screen.findByRole('heading', { level: 1, name: 'Cart' })).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/cart');
  });

  it('a staff login with returnTo=/cart lands in the staff area', async () => {
    const { router } = renderApp('/login?returnTo=%2Fcart');
    await submitLogin(manager.email, manager.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/manage'));
  });
});
