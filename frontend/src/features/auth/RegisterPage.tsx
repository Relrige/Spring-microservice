import { Anchor, Button, PasswordInput, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';
import { login, register } from '../../api/auth';
import { ApiError, MESSAGES } from '../../api/errors';
import { applyServerErrors, FormError } from '../../components/form-errors';
import { roleHome } from '../../session/roles';
import { InvalidTokenError, useSession } from '../../session/SessionProvider';
import { AuthCard } from './AuthCard';

export const EMAIL_TAKEN = 'An account with this email already exists';
export const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** `/register`: customer registration, then automatic login (spec user-session). */
export function RegisterPage() {
  const { session, startSession } = useSession();
  const navigate = useNavigate();
  const [general, setGeneral] = useState<string | null>(null);
  const [emailTaken, setEmailTaken] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [justLoggedIn, setJustLoggedIn] = useState(false);

  // Client-side checks mirror EP-AUTH-01: email at most 256, password 5–64.
  const form = useForm({
    initialValues: { email: '', password: '', confirmPassword: '' },
    validate: {
      email: (value) => {
        const email = value.trim();
        if (!email) return 'Enter your email';
        if (!EMAIL.test(email)) return 'Enter a valid email address';
        if (email.length > 256) return 'Use at most 256 characters';
        return null;
      },
      password: (value) =>
        value.length < 5 || value.length > 64 ? 'Use 5 to 64 characters' : null,
      confirmPassword: (value, values) =>
        value !== values.password ? 'Passwords do not match' : null,
    },
  });

  if (session && !justLoggedIn) return <Navigate to={roleHome(session.role)} replace />;

  const submit = form.onSubmit(async (values) => {
    setGeneral(null);
    setEmailTaken(false);
    setSubmitting(true);
    const credentials = { email: values.email.trim(), password: values.password };
    try {
      await register(credentials);
    } catch (error) {
      setSubmitting(false);
      if (error instanceof ApiError && error.kind === 'conflict') {
        form.setFieldError('email', EMAIL_TAKEN);
        setEmailTaken(true);
      } else {
        setGeneral(applyServerErrors(form, error, ['email', 'password']));
      }
      return;
    }
    try {
      const { token } = await login(credentials);
      setJustLoggedIn(true);
      startSession(token);
      navigate('/', { replace: true });
    } catch (error) {
      setJustLoggedIn(false);
      setGeneral(
        error instanceof InvalidTokenError
          ? MESSAGES.unexpected
          : 'Your account was created, but logging in failed. Please log in.',
      );
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <AuthCard title="Create an account" subtitle="Register to order and follow your deliveries.">
      <form onSubmit={submit} noValidate>
        <Stack gap={20}>
          <FormError message={general} />
          <Stack gap={6}>
            <TextInput
              label="Email"
              type="email"
              autoComplete="email"
              size="md"
              {...form.getInputProps('email')}
            />
            {emailTaken && (
              <Anchor component={Link} to="/login" size="sm" fw={600}>
                Log in with this email instead
              </Anchor>
            )}
          </Stack>
          <PasswordInput
            label="Password"
            description="5 to 64 characters"
            autoComplete="new-password"
            size="md"
            {...form.getInputProps('password')}
          />
          <PasswordInput
            label="Confirm password"
            autoComplete="new-password"
            size="md"
            {...form.getInputProps('confirmPassword')}
          />
          <Button type="submit" size="md" loading={submitting}>
            Create account
          </Button>
        </Stack>
      </form>
      <Text ta="center" size="sm" c="dimmed">
        Already have an account?{' '}
        <Anchor component={Link} to="/login" fw={600}>
          Log in
        </Anchor>
      </Text>
    </AuthCard>
  );
}
