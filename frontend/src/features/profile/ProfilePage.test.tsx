import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { DEMO_PROFILE } from '../../fixtures/demo-data';
import { db } from '../../mocks/db';
import { problem } from '../../mocks/handlers';
import { loginAs, renderApp } from '../../test/render';
import { server } from '../../test/server';

const customerId = () => db.users.find((u) => u.role === 'CUSTOMER')!.id;

function recordProfileSaves() {
  const bodies: unknown[] = [];
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PUT' && request.url.endsWith('/customers/me/profile')) {
      bodies.push(await request.clone().json());
    }
  });
  return bodies;
}

describe('Profile page', () => {
  it('Saved profile is shown', async () => {
    loginAs('CUSTOMER');
    renderApp('/account/profile');
    expect(await screen.findByLabelText('First name')).toHaveValue(DEMO_PROFILE.firstName);
    expect(screen.getByLabelText('Last name')).toHaveValue(DEMO_PROFILE.lastName);
    expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone);
    expect(screen.getByLabelText(/Default delivery address/)).toHaveValue(
      DEMO_PROFILE.defaultAddress,
    );
  });

  it('No profile yet: an empty form and no error', async () => {
    db.profiles.delete(customerId());
    loginAs('CUSTOMER');
    renderApp('/account/profile');
    expect(await screen.findByLabelText('First name')).toHaveValue('');
    expect(screen.getByLabelText('Phone')).toHaveValue('');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('Saving the profile', () => {
  it('Profile saved: the phone is sent without spaces and a confirmation is shown', async () => {
    db.profiles.delete(customerId());
    loginAs('CUSTOMER');
    const saves = recordProfileSaves();
    renderApp('/account/profile');
    await userEvent.type(await screen.findByLabelText('First name'), 'Olena');
    await userEvent.type(screen.getByLabelText('Last name'), 'Kovalenko');
    await userEvent.type(screen.getByLabelText('Phone'), '+380 50 123 45 67');
    await userEvent.type(screen.getByLabelText(/Default delivery address/), 'Kyiv, branch 12');
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByText('Profile saved')).toBeInTheDocument();
    await waitFor(() => expect(saves).toHaveLength(1));
    expect(saves[0]).toMatchObject({ phone: '+380501234567', firstName: 'Olena' });
    expect(db.profiles.get(customerId())).toMatchObject({ lastName: 'Kovalenko' });
  });

  it('Invalid phone: no request, the field asks for the international format', async () => {
    loginAs('CUSTOMER');
    const saves = recordProfileSaves();
    renderApp('/account/profile');
    const phone = await screen.findByLabelText('Phone');
    await userEvent.clear(phone);
    await userEvent.type(phone, '0501234567');
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(
      await screen.findByText('Use the international format, for example +380501234567'),
    ).toBeInTheDocument();
    expect(saves).toEqual([]);
  });

  it('Server field errors: the message is on the phone field, values kept', async () => {
    loginAs('CUSTOMER');
    server.use(
      http.put('*/api/customers/me/profile', () =>
        problem(400, 'Validation failed', { errors: { phone: ['phone is not reachable'] } }),
      ),
    );
    renderApp('/account/profile');
    await screen.findByLabelText('First name');
    await waitFor(() =>
      expect(screen.getByLabelText('First name')).toHaveValue(DEMO_PROFILE.firstName),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(await screen.findByText('phone is not reachable')).toBeInTheDocument();
    expect(screen.getByLabelText('Phone')).toHaveValue(DEMO_PROFILE.phone);
  });
});
