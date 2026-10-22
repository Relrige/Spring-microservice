// `/manage/categories` (spec category-management; design D9).
import { Alert, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertCircle, IconPlus } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { deleteCategory, type Category } from '../../api/catalog';
import { ApiError, userMessage } from '../../api/errors';
import { PageContainer } from '../../app/PageContainer';
import { ShortId } from '../../components/DateTime';
import { ErrorState } from '../../components/ErrorState';
import { Loading } from '../../components/Loading';
import { Paging } from '../catalog/Paging';
import { CategoryDialog } from './CategoryDialog';
import { ConfirmDialog } from './ConfirmDialog';
import { CATEGORY_GONE, CATEGORY_HAS_PRODUCTS } from './messages';
import { StaffSearch } from './StaffSearch';
import { invalidateCatalog, useManagedCategories } from './queries';
import { StaffTable } from './StaffTable';
import { parseCategoriesState, serializeCategoriesState, type CategoriesState } from './url-state';

type Editing = { category: Category | null } | null;

export function CategoriesPage() {
  const [params, setParams] = useSearchParams();
  const state = parseCategoriesState(params);
  const categories = useManagedCategories(state);
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Editing>(null);
  const [deleting, setDeleting] = useState<Category | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const update = (changes: Partial<CategoriesState>) => {
    setMessage(null);
    const next = { ...state, ...changes, page: 'page' in changes ? changes.page! : 1 };
    setParams(serializeCategoriesState(next));
  };

  const remove = async (category: Category) => {
    setDeleting(null);
    setMessage(null);
    try {
      await deleteCategory(category.categoryId);
      notifications.show({ message: 'Category deleted' });
    } catch (error) {
      // `404`: someone deleted it already, which is what the manager wanted.
      if (error instanceof ApiError && error.kind === 'not-found') {
        notifications.show({ message: 'Category deleted' });
      } else {
        setMessage(
          error instanceof ApiError && error.kind === 'conflict'
            ? CATEGORY_HAS_PRODUCTS
            : userMessage(error),
        );
        return;
      }
    }
    await invalidateCatalog(queryClient);
  };

  const total = categories.data?.totalElements;
  const totalPages = categories.data
    ? Math.max(1, Math.ceil(categories.data.totalElements / categories.data.size))
    : 1;

  return (
    <PageContainer>
      <Stack gap="lg">
        <Group justify="space-between" align="flex-end" gap="md">
          <Stack gap={4}>
            <Title order={1}>Categories</Title>
            <Text size="sm" c="dimmed" aria-live="polite">
              {total === undefined ? ' ' : `${total} categor${total === 1 ? 'y' : 'ies'}`}
            </Text>
          </Stack>
          <Button
            leftSection={<IconPlus size={20} stroke={1.75} />}
            onClick={() => {
              setMessage(null);
              setEditing({ category: null });
            }}
          >
            New category
          </Button>
        </Group>

        <Group gap="sm" align="flex-end" role="toolbar" aria-label="Category filters">
          <StaffSearch
            key={state.search}
            initial={state.search}
            label="Search categories"
            placeholder="Part of the name"
            onSearch={(search) => update({ search })}
          />
        </Group>

        {message && (
          <Alert
            role="alert"
            variant="light"
            color="red"
            radius="md"
            icon={<IconAlertCircle size={20} stroke={1.75} />}
            withCloseButton
            closeButtonLabel="Dismiss"
            onClose={() => setMessage(null)}
            styles={{
              root: { background: 'var(--vs-danger-soft)' },
              message: { color: 'var(--vs-danger)' },
              icon: { color: 'var(--vs-danger)' },
            }}
          >
            {message}
          </Alert>
        )}

        {categories.isPending && <Loading lines={6} label="Loading categories…" />}
        {categories.isError && (
          <ErrorState error={categories.error} onRetry={() => void categories.refetch()} />
        )}
        {categories.isSuccess && categories.data.content.length === 0 && (
          <Stack align="center" gap="sm" py={48} ta="center">
            {total ? (
              <>
                <Title order={3}>This page has no categories</Title>
                <Button variant="outline" onClick={() => update({ page: 1 })}>
                  Go to the first page
                </Button>
              </>
            ) : state.search ? (
              <>
                <Title order={3}>No categories match your search</Title>
                <Button variant="outline" onClick={() => update({ search: '' })}>
                  Clear search
                </Button>
              </>
            ) : (
              <Title order={3}>There are no categories yet</Title>
            )}
          </Stack>
        )}
        {categories.isSuccess && categories.data.content.length > 0 && (
          <>
            <StaffTable
              label="Categories"
              minWidth={340}
              columns={[{ label: 'Name' }, { label: 'Id' }, { label: 'Actions', hidden: true }]}
            >
              {categories.data.content.map((category) => (
                <Table.Tr key={category.categoryId} data-testid="category-row">
                  <Table.Td fw={600} fz={14}>
                    {category.name}
                  </Table.Td>
                  <Table.Td c="dimmed">
                    <ShortId id={category.categoryId} />
                  </Table.Td>
                  <Table.Td>
                    <Group gap={6} justify="flex-end" wrap="nowrap">
                      <Button
                        size="compact-sm"
                        variant="default"
                        onClick={() => {
                          setMessage(null);
                          setEditing({ category });
                        }}
                      >
                        Rename
                      </Button>
                      <Button
                        size="compact-sm"
                        variant="default"
                        onClick={() => {
                          setMessage(null);
                          setDeleting(category);
                        }}
                      >
                        Delete
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </StaffTable>
            <Paging page={state.page} totalPages={totalPages} onPage={(page) => update({ page })} />
          </>
        )}
      </Stack>

      {editing && (
        <CategoryDialog
          category={editing.category}
          onClose={() => setEditing(null)}
          onSaved={() => {
            notifications.show({
              message: editing.category ? 'Category renamed' : 'Category created',
            });
            setEditing(null);
            void invalidateCatalog(queryClient);
          }}
          onGone={() => {
            setEditing(null);
            setMessage(CATEGORY_GONE);
            void invalidateCatalog(queryClient);
          }}
        />
      )}
      <ConfirmDialog
        opened={deleting !== null}
        title={`Delete category “${deleting?.name ?? ''}”?`}
        confirmLabel="Delete"
        onCancel={() => setDeleting(null)}
        onConfirm={() => void remove(deleting!)}
      >
        Only a category without products can be deleted.
      </ConfirmDialog>
    </PageContainer>
  );
}
