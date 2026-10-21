import { Alert } from '@mantine/core';
import { IconAlertCircle } from '@tabler/icons-react';
import { ApiError, userMessage } from '../api/errors';

interface FieldErrorTarget {
  setFieldError: (path: string, error: string) => void;
}

/**
 * Spec api-access, Field validation errors on forms: puts each `400` field
 * message on the form field of the same name and returns the rest (and any
 * non-field error) as the text for the form's general error area.
 */
export function applyServerErrors(
  form: FieldErrorTarget,
  error: unknown,
  fields: readonly string[],
): string | null {
  const fieldErrors = error instanceof ApiError ? error.problem?.errors : undefined;
  if (!(error instanceof ApiError) || error.kind !== 'validation' || !fieldErrors) {
    return userMessage(error);
  }
  const general: string[] = [];
  for (const [field, messages] of Object.entries(fieldErrors)) {
    const text = messages.join(' ');
    if (fields.includes(field)) form.setFieldError(field, text);
    else general.push(`${field}: ${text}`);
  }
  return general.length > 0 ? general.join('\n') : null;
}

/** The general error area of a form. */
export function FormError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <Alert
      role="alert"
      variant="light"
      color="red"
      radius="md"
      icon={<IconAlertCircle size={20} stroke={1.75} />}
      styles={{
        root: { background: 'var(--vs-danger-soft)' },
        message: { color: 'var(--vs-danger)', whiteSpace: 'pre-line' },
        icon: { color: 'var(--vs-danger)' },
      }}
    >
      {message}
    </Alert>
  );
}
