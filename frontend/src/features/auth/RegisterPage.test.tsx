import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DEMO_CUSTOMER } from '../../fixtures/demo-data';
import { currentUrl, renderApp } from '../../test/render';
import { server } from '../../test/server';
import { currentUserText } from '../../test/ui';

async function fill(email: string, password: string, confirm = password) {
  await userEvent.type(await screen.findByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.type(screen.getByLabelText('Confirm password'), confirm);
  await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
}

function countRegisterRequests() {
  const counter = { count: 0 };
  server.events.on('request:start', ({ request }) => {
    if (request.url.endsWith('/api/user/register')) counter.count += 1;
  });
  return counter;
}

describe('Customer registration', () => {
  it('Successful registration logs the user in and lands on the storefront', async () => {
    const { router } = renderApp('/register');
    await fill('new.customer@example.com', 'secret1');
    await waitFor(() => expect(currentUrl(router)).toBe('/'));
    expect(currentUserText()).toBe('new.customer@example.com · Customer');
  });

  it('Email already used: message at the email field and a link to log in', async () => {
    renderApp('/register');
    await fill(DEMO_CUSTOMER.email, 'secret1');
    expect(
      await screen.findByText('An account with this email already exists'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('link', { name: 'Log in with this email instead' })).toHaveAttribute(
      'href',
      '/login',
    );
  });

  it('Client-side checks: short password, mismatch and bad email send no request', async () => {
    const requests = countRegisterRequests();
    renderApp('/register');
    await fill('not-an-email', '1234', '4321');
    expect(await screen.findByText('Enter a valid email address')).toBeInTheDocument();
    expect(screen.getByText('Use 5 to 64 characters')).toBeInTheDocument();
    expect(screen.getByText('Passwords do not match')).toBeInTheDocument();
    expect(requests.count).toBe(0);
    server.events.removeAllListeners();
  });
});
