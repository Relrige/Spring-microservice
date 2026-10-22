// "Cancel order" with a confirmation (spec customer-orders, Cancel an order; design D9).
import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { userMessage } from '../../api/errors';
import { cancelOrder, orderProblem, type OrderDetails } from '../../api/order';
import { FormError } from '../../components/form-errors';
import { orderKeys } from './queries';
import { CANCELLABLE } from './status';

interface CancelOrderProps {
  order: OrderDetails;
  /** Called with the status of the `202` answer. */
  onCancelled?: () => void;
}

export function CancelOrder({ order, onCancelled }: CancelOrderProps) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  // A refused cancel keeps its message after the order was read again in its new status.
  if (!CANCELLABLE.has(order.status)) return <FormError message={message} />;
  const paid = order.status !== 'PENDING_PAYMENT';

  const cancel = async () => {
    setConfirming(false);
    setMessage(null);
    setCancelling(true);
    try {
      const answer = await cancelOrder(order.orderId);
      queryClient.setQueryData<OrderDetails>(orderKeys.detail(order.orderId), (old) =>
        old ? { ...old, status: answer.status } : old,
      );
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      onCancelled?.();
    } catch (error) {
      const problem = orderProblem(error);
      if (problem?.kind === 'order-state') {
        setMessage('This order can no longer be cancelled.');
        void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      } else setMessage(userMessage(error));
    } finally {
      setCancelling(false);
    }
  };

  return (
    <Stack gap="xs" align="flex-start">
      <FormError message={message} />
      <Button variant="default" loading={cancelling} onClick={() => setConfirming(true)}>
        Cancel order
      </Button>
      <Modal opened={confirming} onClose={() => setConfirming(false)} title="Cancel this order?">
        <Stack gap="md">
          <Text>
            The reserved products are released.
            {paid ? ' The payment will be refunded.' : ''}
          </Text>
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={() => setConfirming(false)}>
              Keep the order
            </Button>
            <Button color="red" onClick={() => void cancel()}>
              Cancel order
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
