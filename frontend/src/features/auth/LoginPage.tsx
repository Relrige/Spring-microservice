import { Alert, Anchor, Button, PasswordInput, Stack, Text, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconClockHour4 } from '@tabler/icons-react';
import { useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { login } from '../../api/auth';
import { ApiError, MESSAGES } from '../../api/errors';
import { applyServerErrors, FormError } from '../../components/form-errors';
import { landingAfterLogin, roleHome, safeReturnTo } from '../../session/roles';
import { InvalidTokenError, useSession } from '../../session/SessionProvider';
import { AuthCard } from './AuthCard';

export const INVALID_CREDENTIALS = 'Invalid email or password';
export const SESSION_EXPIRED = 'Your session has expired. Log in again to continue where you were.';

/** `/login` for every role (spec user-session, Login). */
export function LoginPage() {
  const { session, startSession } = useSession();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [general, setGeneral] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [justLoggedIn, setJustLoggedIn] = useState(false);
  const returnTo = safeReturnTo(params.get('returnTo'));
  const expired = params.get('expired') === '1';

  const form = useForm({
    initialValues: { email: '', password: '' },
    validate: {
      email: (value) => (value.trim() ? null : 'Enter your email'),
      password: (value) => (value ? null : 'Enter your password'),
    },
  });

  // Spec user-session, Already logged in: go to the role's landing area.
  if (session && !justLoggedIn) return <Navigate to={roleHome(session.role)} replace />;

  const submit = form.onSubmit(async (values) => {
    setGeneral(null);
    setSubmitting(true);
    try {
      const { token } = await login({ email: values.email.trim(), password: values.password });
      setJustLoggedIn(true);
      const started = startSession(token);
      navigate(landingAfterLogin(started.role, returnTo), { replace: true });
    } catch (error) {
      setJustLoggedIn(false);
      if (error instanceof ApiError && error.kind === 'unauthorized') {
        setGeneral(INVALID_CREDENTIALS);
        form.setFieldValue('password', '');
      } else if (error instanceof InvalidTokenError) {
        setGeneral(MESSAGES.unexpected);
      } else {
        setGeneral(applyServerErrors(form, error, ['email', 'password']));
      }
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <AuthCard title="Log in" subtitle="Customers and staff use the same form.">
      {expired && (
        <Alert
          role="status"
          variant="light"
          radius="md"
          icon={<IconClockHour4 size={20} stroke={1.75} />}
          styles={{
            root: { background: 'var(--vs-primary-soft)' },
            message: { color: 'var(--vs-primary)' },
            icon: { color: 'var(--vs-primary)' },
          }}
        >
          {SESSION_EXPIRED}
        </Alert>
      )}
      <form onSubmit={submit} noValidate>
        <Stack gap={20}>
          <FormError message={general} />
          <TextInput
            label="Email"
            type="email"
            autoComplete="email"
            size="md"
            {...form.getInputProps('email')}
          />
          <PasswordInput
            label="Password"
            autoComplete="current-password"
            size="md"
            {...form.getInputProps('password')}
          />
          <Button type="submit" size="md" loading={submitting}>
            Log in
          </Button>
        </Stack>
      </form>
      <Text ta="center" size="sm" c="dimmed">
        New to VoltStore?{' '}
        <Anchor component={Link} to="/register" fw={600}>
          Create an account
        </Anchor>
      </Text>
    </AuthCard>
  );
}
