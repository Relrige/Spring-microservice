import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

function recordCreations() {
  const bodies: unknown[] = [];
  server.events.on('request:start', async ({ request }) => {
    if (request.url.endsWith('/user/non-customer')) bodies.push(await request.clone().json());
  });
  return bodies;
}

async function fill(email: string, password: string, confirm = password, role?: string) {
  loginAs('ADMIN');
  renderApp('/admin');
  await userEvent.type(await screen.findByLabelText('Email'), email);
  await userEvent.type(
    screen.getByLabelText('Password'),
    password,
  );
  await userEvent.type(screen.getByLabelText('Confirm password'), confirm);
  if (role) {
    await userEvent.click(screen.getByRole('combobox', { name: 'Role' }));
    await userEvent.click(await screen.findByRole('option', { name: role }));
  }
}

const submit = () => userEvent.click(screen.getByRole('button', { name: 'Create account' }));

describe('Create a staff account', () => {
  it('Worker account created: role INVENTORY_WORKER, the confirmation, an empty form', async () => {
    const sent = recordCreations();
    await fill(' new.worker@voltstore.com ', 'secret-1', 'secret-1', 'Inventory worker');
    await submit();
    expect(
      await screen.findByText('Account created: new.worker@voltstore.com · Inventory worker'),
    ).toBeInTheDocument();
    expect(sent).toEqual([
      { email: 'new.worker@voltstore.com', password: 'secret-1', role: 'INVENTORY_WORKER' },
    ]);
    expect(screen.getByLabelText('Email')).toHaveValue('');
    expect(db.users.find((u) => u.email === 'new.worker@voltstore.com')?.role).toBe(
      'INVENTORY_WORKER',
    );
  });

  it('No customer role: Catalog manager, Inventory worker and Admin only', async () => {
    loginAs('ADMIN');
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('combobox', { name: 'Role' }));
    const options = await screen.findAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual([
      'Catalog manager',
      'Inventory worker',
      'Admin',
    ]);
  });
});

describe('Account form rules', () => {
  it('Short password: no request, use 5 to 64 characters', async () => {
    const sent = recordCreations();
    await fill('a@voltstore.com', 'abcd', 'abcd', 'Admin');
    await submit();
    expect(await screen.findByText('Use 5 to 64 characters')).toBeInTheDocument();
    expect(sent).toEqual([]);
  });

  it('Passwords differ: no request, the confirmation says they do not match', async () => {
    const sent = recordCreations();
    await fill('a@voltstore.com', 'secret-1', 'secret-2', 'Admin');
    await submit();
    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
    expect(sent).toEqual([]);
  });
});

describe('Rejected account', () => {
  it('Email in use: the message on the email field, the role kept', async () => {
    await fill('manager@voltstore.com', 'secret-1', 'secret-1', 'Catalog manager');
    await submit();
    expect(
      await screen.findByText('An account with this email already exists'),
    ).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Role' })).toHaveValue('Catalog manager');
  });

  it('Server field error: errors.role on the role field', async () => {
    server.use(
      http.post('*/api/user/non-customer', () =>
        problem(400, 'Validation failed', { errors: { role: ['must be a known role'] } }),
      ),
    );
    await fill('b@voltstore.com', 'secret-1', 'secret-1', 'Admin');
    await submit();
    await waitFor(() => expect(screen.getByText('must be a known role')).toBeInTheDocument());
  });
});
