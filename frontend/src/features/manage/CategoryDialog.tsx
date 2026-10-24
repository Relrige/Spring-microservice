// Create or rename a category (spec category-management; design D9).
import { Button, Group, Modal, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { createCategory, renameCategory, type Category } from '../../api/catalog';
import { ApiError } from '../../api/errors';
import { applyServerErrors, FormError } from '../../components/form-errors';
import { categoryNameError } from './field-rules';
import { CATEGORY_NAME_IN_USE } from './messages';

interface CategoryDialogProps {
  /** `null` creates a new category; a category is renamed. */
  category: Category | null;
  onClose: () => void;
  onSaved: (category: Category) => void;
  /** A rename answered `404`: the dialog closes and the page explains. */
  onGone: () => void;
}

/** Mount it only while open, so each opening starts from the current name. */
export function CategoryDialog({ category, onClose, onSaved, onGone }: CategoryDialogProps) {
  const [general, setGeneral] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const form = useForm({
    initialValues: { name: category?.name ?? '' },
    validate: { name: categoryNameError },
  });

  const submit = form.onSubmit(async ({ name }) => {
    setGeneral(null);
    setPending(true);
    try {
      const trimmed = name.trim();
      const saved = category
        ? await renameCategory(category.categoryId, trimmed)
        : await createCategory(trimmed);
      onSaved(saved);
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'conflict') {
        form.setFieldError('name', CATEGORY_NAME_IN_USE);
      } else if (category && error instanceof ApiError && error.kind === 'not-found') onGone();
      else setGeneral(applyServerErrors(form, error, ['name']));
    } finally {
      setPending(false);
    }
  });

  return (
    <Modal opened onClose={onClose} title={category ? 'Rename category' : 'New category'}>
      <form onSubmit={submit} noValidate>
        <Stack gap="md">
          <FormError message={general} />
          <TextInput label="Name" data-autofocus {...form.getInputProps('name')} />
          <Group justify="flex-end" gap="sm">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={pending}>
              {category ? 'Rename' : 'Create category'}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
