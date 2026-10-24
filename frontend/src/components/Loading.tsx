import { Skeleton, Stack, VisuallyHidden } from '@mantine/core';

interface LoadingProps {
  /** Number of skeleton lines. */
  lines?: number;
  label?: string;
}

/** Skeleton blocks in `surface-sunken` shown while content loads (design-system README, States). */
export function Loading({ lines = 3, label = 'Loading…' }: LoadingProps) {
  return (
    <Stack gap="sm" role="status" aria-busy="true" aria-live="polite">
      <VisuallyHidden>{label}</VisuallyHidden>
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton
          key={index}
          height={20}
          width={index === lines - 1 ? '60%' : '100%'}
          radius="sm"
        />
      ))}
    </Stack>
  );
}
