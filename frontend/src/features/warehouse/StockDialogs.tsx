// Adjust and packaging dialogs of the stock page (spec warehouse-stock; design D5–D6).
import { Button, Group, Modal, NumberInput, SegmentedControl, Stack, Text } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError, userMessage } from '../../api/errors';
import { adjustStock, setPackaging, type StockItem } from '../../api/inventory';
import { applyServerErrors, FormError } from '../../components/form-errors';

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
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // BL-03: workers cannot read stored packaging yet, so the form always starts empty.
  const form = useForm<Record<(typeof FIELDS)[number][0], string | number>>({
    initialValues: { grossWeightGrams: '', lengthCm: '', widthCm: '', heightCm: '' },
    validate: {
      grossWeightGrams: wholeAboveZero,
      lengthCm: wholeAboveZero,
      widthCm: wholeAboveZero,
      heightCm: wholeAboveZero,
    },
  });
  const close = () => {
    form.reset();
    setMessage(null);
    onClose();
  };

  const submit = form.onSubmit(async (values) => {
    if (!item) return;
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
    <Modal opened={item !== null} onClose={close} title="Set packaging">
      {item && (
        <form onSubmit={submit} noValidate>
          <Stack gap="md">
            <Text fw={600}>{nameOf(item)}</Text>
            <Text fz={14} c="dimmed">
              Needed before the product can be activated and shipped. All four values are replaced
              together.
            </Text>
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
      )}
    </Modal>
  );
}
