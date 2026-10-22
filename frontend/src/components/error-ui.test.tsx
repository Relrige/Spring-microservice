import { Button, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/errors';
import { request } from '../api/http';
import { renderWithProviders } from '../test/render';
import { server } from '../test/server';
import { ErrorState } from './ErrorState';
import { applyServerErrors, FormError } from './form-errors';
import { Loading } from './Loading';

function DemoForm() {
  const form = useForm({ initialValues: { email: '', name: '' } });
  const [general, setGeneral] = useState<string | null>(null);
  return (
    <form
      onSubmit={form.onSubmit(async (values) => {
        try {
          await request('/demo', { method: 'POST', body: values });
        } catch (error) {
          setGeneral(applyServerErrors(form, error, ['email', 'name']));
        }
      })}
    >
      <FormError message={general} />
      <TextInput label="Email" {...form.getInputProps('email')} />
      <TextInput label="Name" {...form.getInputProps('name')} />
      <Button type="submit">Send</Button>
    </form>
  );
}

describe('Field validation errors on forms', () => {
  it('Server rejects a field: shows it at the field and keeps the values', async () => {
    server.use(
      http.post('*/api/demo', () =>
        HttpResponse.json(
          { status: 400, errors: { email: ['must be a well-formed email address'] } },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(<DemoForm />);
    await userEvent.type(screen.getByLabelText('Email'), 'not-an-email');
    await userEvent.type(screen.getByLabelText('Name'), 'Olena');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));

    const email = screen.getByLabelText('Email');
    expect(await screen.findByText('must be a well-formed email address')).toBeInTheDocument();
    expect(email).toHaveAttribute('aria-invalid', 'true');
    expect(email).toHaveValue('not-an-email');
    expect(screen.getByLabelText('Name')).toHaveValue('Olena');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('Error for a field not on the form: shown in the general area', async () => {
    server.use(
      http.post('*/api/demo', () =>
        HttpResponse.json({ status: 400, errors: { role: ['must not be null'] } }, { status: 400 }),
      ),
    );
    renderWithProviders(<DemoForm />);
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('role: must not be null');
  });

  it('Bad request without field errors: detail in the general area, values kept', async () => {
    server.use(
      http.post('*/api/demo', () =>
        HttpResponse.json(
          {
            type: 'https://voltstore.com/errors/bad-request',
            title: 'Bad Request',
            status: 400,
            detail: 'Malformed JSON request',
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    renderWithProviders(<DemoForm />);
    await userEvent.type(screen.getByLabelText('Email'), 'olena@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Malformed JSON request');
    expect(screen.getByLabelText('Email')).toHaveValue('olena@example.com');
    expect(screen.getByLabelText('Email')).not.toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Name')).not.toHaveAttribute('aria-invalid', 'true');
  });
});

describe('ErrorState', () => {
  it('Gateway cannot reach a service: temporarily unavailable with Try again', async () => {
    const retry = vi.fn();
    renderWithProviders(
      <ErrorState error={new ApiError('unavailable', { status: 502 })} onRetry={retry} />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('temporarily unavailable');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('Network down: tells the user to check the connection', () => {
    renderWithProviders(<ErrorState error={new ApiError('network')} />);
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Cannot reach VoltStore. Check your connection and try again.',
    );
  });

  it('Internal error: generic message and no technical details', () => {
    const error = new ApiError('unexpected', {
      status: 500,
      problem: { detail: 'java.lang.NullPointerException' },
    });
    renderWithProviders(<ErrorState error={error} />);
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong. Please try again.');
    expect(alert).not.toHaveTextContent('NullPointerException');
  });
});

describe('Loading', () => {
  it('announces loading', () => {
    renderWithProviders(<Loading />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading…');
  });
});
