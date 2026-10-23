import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';

interface ConfirmDialogProps {
  opened: boolean;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

/** A small confirmation before a change that cannot simply be undone (design D7, D9). */
export function ConfirmDialog({
  opened,
  title,
  children,
  confirmLabel,
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <Modal opened={opened} onClose={onCancel} title={title}>
      <Stack gap="md">
        <Text>{children}</Text>
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onCancel}>
            {cancelLabel}
          </Button>
          <Button color="red" onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
