import { Alert, Button, Stack, Text } from '@mantine/core';
import { IconAlertCircle } from '@tabler/icons-react';
import { userMessage } from '../api/errors';

interface ErrorStateProps {
  error: unknown;
  /** Shown as "Try again"; reloads what failed (never resends a change automatically). */
  onRetry?: () => void;
  title?: string;
}

/** A failed load shown in place of the content (spec api-access). */
export function ErrorState({ error, onRetry, title }: ErrorStateProps) {
  return (
    <Alert
      role="alert"
      variant="light"
      color="red"
      radius="md"
      icon={<IconAlertCircle size={20} stroke={1.75} />}
      title={title}
      styles={{
        root: { background: 'var(--vs-danger-soft)' },
        message: { color: 'var(--vs-danger)' },
        title: { color: 'var(--vs-danger)' },
        icon: { color: 'var(--vs-danger)' },
      }}
    >
      <Stack gap="sm" align="flex-start">
        <Text inherit>{userMessage(error)}</Text>
        {onRetry && (
          <Button variant="outline" color="red" size="sm" onClick={onRetry}>
            Try again
          </Button>
        )}
      </Stack>
    </Alert>
  );
}
