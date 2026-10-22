import { Button, Group, Stack, Text, Textarea, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { saveProfile } from '../../api/customer';
import { PageContainer } from '../../app/PageContainer';
import { ErrorState } from '../../components/ErrorState';
import { applyServerErrors, FormError } from '../../components/form-errors';
import { Loading } from '../../components/Loading';
import { normalizePhone, phoneError, profileKey, useProfile } from './profile';

const FIELDS = ['firstName', 'lastName', 'phone', 'defaultAddress'] as const;

const nameError = (label: string) => (value: string) =>
  !value.trim() ? `Enter your ${label}` : value.length > 100 ? 'Use at most 100 characters' : null;

/** `/account/profile` (spec customer-profile). */
export function ProfilePage() {
  const profile = useProfile();
  const queryClient = useQueryClient();
  const [general, setGeneral] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const form = useForm({
    initialValues: { firstName: '', lastName: '', phone: '', defaultAddress: '' },
    validate: {
      firstName: nameError('first name'),
      lastName: nameError('last name'),
      phone: phoneError,
      defaultAddress: (value) => (value.length > 500 ? 'Use at most 500 characters' : null),
    },
  });

  const { data } = profile;
  const { initialize } = form;
  useEffect(() => {
    // A profile never saved has every field `null`: the form starts empty.
    if (data) {
      initialize({
        firstName: data.firstName ?? '',
        lastName: data.lastName ?? '',
        phone: data.phone ?? '',
        defaultAddress: data.defaultAddress ?? '',
      });
    }
  }, [data, initialize]);

  const submit = form.onSubmit(async (values) => {
    setGeneral(null);
    setSaving(true);
    try {
      const saved = await saveProfile({
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        phone: normalizePhone(values.phone),
        defaultAddress: values.defaultAddress.trim() || undefined,
      });
      queryClient.setQueryData(profileKey, saved);
      form.setValues({ phone: saved.phone ?? '', defaultAddress: saved.defaultAddress ?? '' });
      notifications.show({ message: 'Profile saved' });
    } catch (error) {
      setGeneral(applyServerErrors(form, error, FIELDS));
    } finally {
      setSaving(false);
    }
  });

  return (
    <PageContainer>
      <Stack gap="lg" maw={560}>
        <Stack gap={4}>
          <Title order={1}>Profile</Title>
          <Text c="dimmed">Your details fill in the delivery form at checkout.</Text>
        </Stack>
        {profile.isPending ? (
          <Loading lines={4} label="Loading your profile…" />
        ) : profile.isError ? (
          <ErrorState error={profile.error} onRetry={() => void profile.refetch()} />
        ) : (
          <form onSubmit={submit} noValidate>
            <Stack gap="md">
              <FormError message={general} />
              <Group grow align="flex-start" wrap="wrap">
                <TextInput
                  label="First name"
                  autoComplete="given-name"
                  {...form.getInputProps('firstName')}
                />
                <TextInput
                  label="Last name"
                  autoComplete="family-name"
                  {...form.getInputProps('lastName')}
                />
              </Group>
              <TextInput
                label="Phone"
                type="tel"
                autoComplete="tel"
                placeholder="+380 50 123 45 67"
                {...form.getInputProps('phone')}
              />
              <Textarea
                label="Default delivery address"
                description="Optional. City, carrier branch or street address."
                autosize
                minRows={2}
                {...form.getInputProps('defaultAddress')}
              />
              <Group>
                <Button type="submit" loading={saving}>
                  Save profile
                </Button>
              </Group>
            </Stack>
          </form>
        )}
      </Stack>
    </PageContainer>
  );
}
