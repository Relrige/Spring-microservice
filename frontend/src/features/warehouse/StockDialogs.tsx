// Adjust and packaging dialogs of the stock page (spec warehouse-stock; design D5–D6).
import { Button, Group, Modal, NumberInput, SegmentedControl, Stack, Text } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError, userMessage } from '../../api/errors';
import {
  adjustStock,
  getStockItem,
  setPackaging,
  type StockItem,
  type StockItemDetails,
} from '../../api/inventory';
import { ErrorState } from '../../components/ErrorState';
import { applyServerErrors, FormError } from '../../components/form-errors';
import { Loading } from '../../components/Loading';

export const stockKey = ['stock'] as const;

const wholeAboveZero = (value: string | number) =>
  value === ''
    ? 'Required'
    : Number.isInteger(Number(value)) && Number(value) >= 1
      ? null
      : 'Enter a whole number above 0';

const nameOf = (item: StockItem) => item.title ?? item.productId;

export function AdjustDialog({ item, onClose }: { item: StockItem | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const form = useForm({
    initialValues: { mode: 'restock', quantity: '' as string | number },
    validate: { quantity: wholeAboveZero },
  });
  const close = () => {
    form.reset();
    setMessage(null);
    onClose();
  };

  const submit = form.onSubmit(async ({ mode, quantity }) => {
    if (!item) return;
    setMessage(null);
    setSaving(true);
    try {
      await adjustStock(item.productId, (mode === 'restock' ? 1 : -1) * Number(quantity));
      await queryClient.invalidateQueries({ queryKey: stockKey });
      close();
    } catch (error) {
      const status = error instanceof ApiError ? error.status : undefined;
      setMessage(
        status === 422
          ? 'Not enough available stock to write off'
          : status === 404
            ? 'This product no longer exists in the catalog'
            : userMessage(error),
      );
    } finally {
      setSaving(false);
    }
  });

  return (
    <Modal opened={item !== null} onClose={close} title="Adjust stock">
      {item && (
        <form onSubmit={submit} noValidate>
          <Stack gap="md">
            <Text fw={600}>{nameOf(item)}</Text>
            <Text fz={14} c="dimmed">
              Available now: {item.availableQuantity}
            </Text>
            <FormError message={message} />
            <SegmentedControl
              aria-label="Adjustment"
              data={[
                { label: 'Restock', value: 'restock' },
                { label: 'Write off', value: 'writeoff' },
              ]}
              {...form.getInputProps('mode')}
            />
            <NumberInput
              label="Quantity"
              min={1}
              allowDecimal={false}
              allowNegative={false}
              {...form.getInputProps('quantity')}
            />
            <Group justify="flex-end" gap="sm">
              <Button variant="default" onClick={close}>
                Cancel
              </Button>
              <Button type="submit" loading={saving}>
                Save
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </Modal>
  );
}

const FIELDS = [
  ['grossWeightGrams', 'Gross weight, g'],
  ['lengthCm', 'Length, cm'],
  ['widthCm', 'Width, cm'],
  ['heightCm', 'Height, cm'],
] as const;

export function PackagingDialog({
  item,
  onClose,
}: {
  item: StockItem | null;
  onClose: () => void;
}) {
  return (
    <Modal opened={item !== null} onClose={onClose} title="Set packaging">
      {item && <PackagingContent key={item.productId} item={item} onClose={onClose} />}
    </Modal>
  );
}

/** Reads the stored values first (EP-INV-08), so the form starts from them (change packaging-prefill, D1). */
function PackagingContent({ item, onClose }: { item: StockItem; onClose: () => void }) {
  const stored = useQuery({
    queryKey: [...stockKey, item.productId],
    queryFn: () => getStockItem(item.productId),
  });
  return (
    <Stack gap="md">
      <Text fw={600}>{nameOf(item)}</Text>
      <Text fz={14} c="dimmed">
        Needed before the product can be activated and shipped. All four values are replaced
        together.
      </Text>
      {stored.isPending ? (
        <Loading lines={2} label="Loading packaging…" />
      ) : stored.isError ? (
        <ErrorState error={stored.error} onRetry={() => void stored.refetch()} />
      ) : (
        <PackagingForm item={item} stored={stored.data} onClose={onClose} />
      )}
    </Stack>
  );
}

function PackagingForm({
  item,
  stored,
  onClose,
}: {
  item: StockItem;
  stored: StockItemDetails;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const form = useForm<Record<(typeof FIELDS)[number][0], string | number>>({
    // A value that is not set yet (missing or null) starts empty.
    initialValues: {
      grossWeightGrams: stored.grossWeightGrams ?? '',
      lengthCm: stored.lengthCm ?? '',
      widthCm: stored.widthCm ?? '',
      heightCm: stored.heightCm ?? '',
    },
    validate: {
      grossWeightGrams: wholeAboveZero,
      lengthCm: wholeAboveZero,
      widthCm: wholeAboveZero,
      heightCm: wholeAboveZero,
    },
  });
  const close = onClose;

  const submit = form.onSubmit(async (values) => {
    setMessage(null);
    setSaving(true);
    try {
      await setPackaging(item.productId, {
        grossWeightGrams: Number(values.grossWeightGrams),
        lengthCm: Number(values.lengthCm),
        widthCm: Number(values.widthCm),
        heightCm: Number(values.heightCm),
      });
      await queryClient.invalidateQueries({ queryKey: stockKey });
      close();
    } catch (error) {
      setMessage(
        applyServerErrors(
          form,
          error,
          FIELDS.map(([field]) => field),
        ),
      );
    } finally {
      setSaving(false);
    }
  });

  return (
    <form onSubmit={submit} noValidate>
      <Stack gap="md">
        <FormError message={message} />
        <Group grow align="flex-start" wrap="wrap">
          {FIELDS.map(([field, label]) => (
            <NumberInput
              key={field}
              label={label}
              min={1}
              allowDecimal={false}
              allowNegative={false}
              miw={120}
              {...form.getInputProps(field)}
            />
          ))}
        </Group>
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={close}>
            Cancel
          </Button>
          <Button type="submit" loading={saving}>
            Save packaging
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
