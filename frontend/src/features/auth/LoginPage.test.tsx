import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { DEMO_CUSTOMER, DEMO_STAFF } from '../../fixtures/demo-data';
import { SESSION_STORAGE_KEY } from '../../session/SessionProvider';
import { currentUrl, loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';
import { currentUserText, submitLogin } from '../../test/ui';

const worker = DEMO_STAFF.find((a) => a.role === 'INVENTORY_WORKER')!;

describe('Login', () => {
  it('Successful login: session starts and a customer lands on the storefront', async () => {
    const { router } = renderApp('/login');
    await submitLogin(DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/'));
    expect(currentUserText()).toBe(`${DEMO_CUSTOMER.email} · Customer`);
    expect(window.localStorage.getItem(SESSION_STORAGE_KEY)).toBeTruthy();
  });

  it('Successful login: goes to the kept return page', async () => {
    const { router } = renderApp('/login?returnTo=%2Faccount%2Forders');
    await submitLogin(DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/account/orders'));
  });

  it('Worker logs in: lands on /warehouse', async () => {
    const { router } = renderApp('/login');
    await submitLogin(worker.email, worker.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/warehouse'));
  });

  it('ignores an external returnTo', async () => {
    const { router } = renderApp('/login?returnTo=https%3A%2F%2Fevil.example%2F');
    await submitLogin(worker.email, worker.password);
    await waitFor(() => expect(currentUrl(router)).toBe('/warehouse'));
  });

  it('Invalid credentials: message, email kept, password cleared, no redirect', async () => {
    const { router } = renderApp('/login');
    await submitLogin(DEMO_CUSTOMER.email, 'wrong-password');
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password');
    expect(screen.getByLabelText('Email')).toHaveValue(DEMO_CUSTOMER.email);
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(currentUrl(router)).toBe('/login');
    expect(screen.queryByText(/session has expired/)).not.toBeInTheDocument();
  });

  it('Already logged in: sent to the role landing area', async () => {
    loginAs('CATALOG_MANAGER');
    const { router } = renderApp('/login');
    await waitFor(() => expect(currentUrl(router)).toBe('/manage'));
  });

  it('Unreadable token: no session starts and a generic error is shown', async () => {
    server.use(http.post('*/api/auth/login', () => HttpResponse.json({ token: 'garbage' })));
    renderApp('/login');
    await submitLogin(DEMO_CUSTOMER.email, DEMO_CUSTOMER.password);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Something went wrong. Please try again.',
    );
    expect(window.localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull();
    expect(currentUserText()).toBeNull();
  });

  it('shows the expired-session notice', async () => {
    renderApp('/login?expired=1');
    expect(await screen.findByText(/Your session has expired/)).toBeInTheDocument();
  });
});
