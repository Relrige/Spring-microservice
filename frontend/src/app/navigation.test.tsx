import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DEMO_CUSTOMER, DEMO_STAFF } from '../fixtures/demo-data';
import { writeGuestCart } from '../features/cart/guest-cart';
import { db } from '../mocks/db';
import { recordCartRequests } from '../test/cart';
import { server } from '../test/server';
import { currentUrl, loginAs, renderApp } from '../test/render';
import { currentUserText, submitLogin } from '../test/ui';

const manager = DEMO_STAFF.find((a) => a.role === 'CATALOG_MANAGER')!;

describe('Protected pages need a login', () => {
  it('Anonymous visitor opens a protected page: login, then back to it', async () => {
    const { router } = renderApp('/account/orders');
    await waitFor(() => expect(currentUrl(router)).toBe('/login?returnTo=%2Faccount%2Forders'));
    await submitLogin(DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/account/orders'));
    expect(await screen.findByRole('heading', { level: 1, name: 'Orders' })).toBeInTheDocument();
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

  it('Manager opens the manager area: the product list at /manage/products', async () => {
    loginAs('CATALOG_MANAGER');
    const { router } = renderApp('/manage');
    expect(await screen.findByRole('heading', { level: 1, name: 'Products' })).toBeInTheDocument();
    expect(currentUrl(router)).toBe('/manage/products');
  });

  it('Unknown manager page: the not-found page', async () => {
    loginAs('CATALOG_MANAGER');
    renderApp('/manage/no-such-page');
    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });

  it('Admin cannot open a nested manager page either', async () => {
    loginAs('ADMIN');
    renderApp('/manage/categories');
    expect(await screen.findByText('You do not have access to this page')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Categories' })).not.toBeInTheDocument();
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
  it('Visitor header: catalog, the cart, Log in and Register, no protected areas', async () => {
    renderApp('/');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Catalog' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Cart' })).toHaveAttribute('href', '/cart');
    expect(screen.queryByTestId('cart-count')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Log in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Register' })).toBeInTheDocument();
    for (const name of ['Catalog console', 'Warehouse', 'Admin']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument();
    }
  });

  it('Manager header: Products, Categories, email and role, Log out; no cart or other areas', async () => {
    loginAs('CATALOG_MANAGER');
    renderApp('/manage/products/00000000-0000-4000-8000-000000000001');
    const products = await screen.findByRole('link', { name: 'Products' });
    expect(products).toHaveAttribute('href', '/manage/products');
    // Nested manager pages highlight their link (design D4).
    expect(products).toHaveAttribute('aria-current', 'page');
    expect(products.getAttribute('style')).toContain('var(--vs-primary-soft)');
    const categories = screen.getByRole('link', { name: 'Categories' });
    expect(categories).toHaveAttribute('href', '/manage/categories');
    expect(categories).not.toHaveAttribute('aria-current');
    expect(categories.getAttribute('style')).not.toContain('var(--vs-primary-soft)');
    expect(currentUserText()).toBe(`${manager.email} · Catalog manager`);
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument();
    for (const name of [
      'Catalog console',
      'Orders',
      'Warehouse',
      'Admin',
      'Log in',
      'Register',
      /^Cart/,
    ]) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument();
    }
  });

  it('Count after adding: the badge shows the quantity a visitor added', async () => {
    renderApp('/');
    await screen.findByRole('link', { name: 'Cart' });
    act(() => writeGuestCart([{ productId: db.products[0]!.productId, quantity: 2 }]));
    expect(await screen.findByRole('link', { name: 'Cart, 2 items' })).toBeInTheDocument();
    expect(screen.getByTestId('cart-count')).toHaveTextContent('2');
  });

  it('Customer header on a phone: the cart with its count is visible without the menu', async () => {
    window.innerWidth = 375;
    loginAs('CUSTOMER');
    renderApp('/');
    expect(await screen.findByRole('link', { name: 'Cart, 4 items' })).toBeInTheDocument();
    expect(screen.getByTestId('cart-count')).toHaveTextContent('4');
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument();
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
    await waitFor(() => expect(currentUrl(router)).toBe('/manage/products'));
  });
});

describe('Customer areas', () => {
  it('Worker opens the checkout: access denied and no order request', async () => {
    loginAs('INVENTORY_WORKER');
    const calls: string[] = [];
    server.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname.startsWith('/api/orders')) calls.push(request.url);
    });
    renderApp('/checkout');
    expect(await screen.findByText('You do not have access to this page')).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('Customer opens the account: the order history is shown', async () => {
    loginAs('CUSTOMER');
    const { router } = renderApp('/account');
    await waitFor(() => expect(currentUrl(router)).toBe('/account/orders'));
    expect(await screen.findByRole('heading', { level: 1, name: 'Orders' })).toBeInTheDocument();
  });

  it('Visitor opens the checkout: login, then the checkout', async () => {
    const { router } = renderApp('/checkout');
    await waitFor(() => expect(currentUrl(router)).toBe('/login?returnTo=%2Fcheckout'));
    await submitLogin(DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/checkout'));
  });

  it('Customer header: catalog, Orders, Profile, the cart and Log out', async () => {
    loginAs('CUSTOMER');
    renderApp('/');
    const nav = await screen.findByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Catalog' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Orders' })).toHaveAttribute(
      'href',
      '/account/orders',
    );
    expect(within(nav).getByRole('link', { name: 'Profile' })).toHaveAttribute(
      'href',
      '/account/profile',
    );
    expect(await screen.findByRole('link', { name: /^Cart/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Log out' })).toBeInTheDocument();
  });
});
