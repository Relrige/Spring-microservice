import { Button, Group } from '@mantine/core';

interface PagingProps {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}

/** Up to five page numbers around the current one. */
function window5(page: number, totalPages: number): number[] {
  const start = Math.max(1, Math.min(page - 2, totalPages - 4));
  const end = Math.min(totalPages, start + 4);
  return Array.from({ length: end - start + 1 }, (_, index) => start + index);
}

/** Previous / numbered pages / Next; controls leading outside the result are disabled. */
export function Paging({ page, totalPages, onPage }: PagingProps) {
  if (totalPages <= 1) return null;
  return (
    <Group component="nav" aria-label="Pages" justify="center" gap="sm">
      <Button variant="default" disabled={page <= 1} onClick={() => onPage(page - 1)}>
        Previous
      </Button>
      {window5(page, totalPages).map((number) => (
        <Button
          key={number}
          variant={number === page ? 'filled' : 'subtle'}
          color={number === page ? undefined : 'gray'}
          miw={40}
          px="sm"
          aria-label={`Page ${number}`}
          aria-current={number === page ? 'page' : undefined}
          onClick={() => onPage(number)}
          styles={number === page ? undefined : { label: { color: 'var(--vs-ink)' } }}
        >
          {number}
        </Button>
      ))}
      <Button variant="default" disabled={page >= totalPages} onClick={() => onPage(page + 1)}>
        Next
      </Button>
    </Group>
  );
}
