import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/** Fills and submits the login form. */
export async function submitLogin(email: string, password: string) {
  await userEvent.type(await screen.findByLabelText('Email'), email);
  await userEvent.type(screen.getByLabelText('Password'), password);
  await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
}

/** The "email · role" text of the header, or null for a visitor. */
export function currentUserText(): string | null {
  return screen.queryByTestId('current-user')?.textContent ?? null;
}
