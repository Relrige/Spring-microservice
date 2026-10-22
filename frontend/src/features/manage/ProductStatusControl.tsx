// Activate / Deactivate (spec product-management; design D7).
import { Button } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { setProductStatus, type ProductStatus } from '../../api/catalog';
import { StatusChip } from '../../components/StatusChip';
import { ConfirmDialog } from './ConfirmDialog';
import { statusErrorMessage } from './messages';
import { invalidateCatalog } from './queries';

/** "Active" `success`, "Inactive" `warning` (design-system README, staff chips). */
export function ProductStatusChip({ status }: { status: ProductStatus }) {
  return status === 'ACTIVE' ? (
    <StatusChip tone="success">Active</StatusChip>
  ) : (
    <StatusChip tone="warning">Inactive</StatusChip>
  );
}

export interface StatusFailure {
  message: string;
  /** An overlapping save (`409`): the page reloads or refetches. */
  conflict: boolean;
}

interface ProductStatusControlProps {
  product: { productId: string; title: string; status: ProductStatus };
  /** Compact for table rows, full for the edit page's status panel. */
  compact?: boolean;
  /** Called when a change starts (to clear an earlier message) and when it fails. */
  onFailure: (failure: StatusFailure | null) => void;
}

export function ProductStatusControl({
  product,
  compact = false,
  onFailure,
}: ProductStatusControlProps) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const activate = product.status === 'NOT_ACTIVE';
  const change = useMutation({
    mutationFn: (status: ProductStatus) => setProductStatus(product.productId, status),
    onMutate: () => onFailure(null),
    onSuccess: async (_, status) => {
      notifications.show({
        message: status === 'ACTIVE' ? 'Product activated' : 'Product deactivated',
      });
      // The status shown afterwards is the one read back from Catalog, never assumed.
      await invalidateCatalog(queryClient);
    },
    onError: (error) => onFailure(statusErrorMessage(error)),
  });

  return (
    <>
      <Button
        size={compact ? 'compact-sm' : 'sm'}
        variant={activate ? 'outline' : 'default'}
        disabled={change.isPending}
        loading={!compact && change.isPending}
        onClick={() => (activate ? change.mutate('ACTIVE') : setConfirming(true))}
      >
        {activate ? 'Activate' : 'Deactivate'}
      </Button>
      <ConfirmDialog
        opened={confirming}
        title="Deactivate this product?"
        confirmLabel="Deactivate"
        onCancel={() => setConfirming(false)}
        onConfirm={() => {
          setConfirming(false);
          change.mutate('NOT_ACTIVE');
        }}
      >
        Customers will no longer see it in the shop.
      </ConfirmDialog>
    </>
  );
}
