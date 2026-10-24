// `/admin` (spec staff-accounts; change admin-staff-accounts, design D1–D2).
import { Alert, Button, PasswordInput, Select, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { createNonCustomer, ROLES, type NonCustomerRequest } from '../../api/auth';
import { ApiError } from '../../api/errors';
import { PageContainer } from '../../app/PageContainer';
import { applyServerErrors, FormError } from '../../components/form-errors';
import { ROLE_LABELS } from '../../session/roles';
import { EMAIL, EMAIL_TAKEN } from '../auth/RegisterPage';

const STAFF_ROLES = ROLES.filter((role) => role !== 'CUSTOMER').map((role) => ({
  value: role,
  label: ROLE_LABELS[role],
}));

export function StaffAccountsPage() {
  const [created, setCreated] = useState<string | null>(null);
  const [general, setGeneral] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // Rules of EP-AUTH-01/03, as on the register page.
  const form = useForm({
    initialValues: { email: '', password: '', confirmPassword: '', role: '' },
    validate: {
      email: (value) => {
        const email = value.trim();
        if (!email) return 'Enter an email';
        if (!EMAIL.test(email)) return 'Enter a valid email address';
        return email.length > 256 ? 'Use at most 256 characters' : null;
      },
      password: (value) =>
        value.length < 5 || value.length > 64 ? 'Use 5 to 64 characters' : null,
      confirmPassword: (value, values) =>
        value !== values.password ? 'Passwords do not match' : null,
      role: (value) => (value ? null : 'Choose a role'),
    },
  });

  const submit = form.onSubmit(async (values) => {
    setCreated(null);
    setGeneral(null);
    setSaving(true);
    const role = values.role as NonCustomerRequest['role'];
    const email = values.email.trim();
    try {
      await createNonCustomer({ email, password: values.password, role });
      setCreated(`Account created: ${email} · ${ROLE_LABELS[role]}`);
      form.reset();
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'conflict') {
        form.setFieldError('email', EMAIL_TAKEN);
      } else {
        setGeneral(applyServerErrors(form, error, ['email', 'password', 'role']));
      }
    } finally {
      setSaving(false);
    }
  });

  return (
    <PageContainer>
      <Stack gap="lg" maw={480}>
        <Stack gap={4}>
          <Title order={1}>Staff accounts</Title>
          <Text c="dimmed">
            Create accounts for catalog managers, inventory workers and admins.
          </Text>
        </Stack>
        {created && (
          <Alert
            role="status"
            variant="light"
            styles={{
              root: { background: 'var(--vs-success-soft)' },
              message: { color: 'var(--vs-success)' },
            }}
          >
            {created}
          </Alert>
        )}
        <form onSubmit={submit} noValidate>
          <Stack gap="md">
            <FormError message={general} />
            <TextInput
              label="Email"
              type="email"
              autoComplete="off"
              {...form.getInputProps('email')}
            />
            <PasswordInput
              label="Password"
              description="5 to 64 characters"
              autoComplete="new-password"
              {...form.getInputProps('password')}
            />
            <PasswordInput
              label="Confirm password"
              autoComplete="new-password"
              {...form.getInputProps('confirmPassword')}
            />
            <Select
              label="Role"
              data={STAFF_ROLES}
              allowDeselect={false}
              {...form.getInputProps('role')}
            />
            <Button type="submit" loading={saving} style={{ alignSelf: 'flex-start' }}>
              Create account
            </Button>
          </Stack>
        </form>
      </Stack>
    </PageContainer>
  );
}
