import { Table, VisuallyHidden } from '@mantine/core';
import type { ReactNode } from 'react';

interface Column {
  label: string;
  /** Kept for screen readers only (image and actions columns). */
  hidden?: boolean;
  align?: 'left' | 'right';
}

interface StaffTableProps {
  label: string;
  columns: Column[];
  /** Below this width the table scrolls inside its own area, not the page. */
  minWidth: number;
  children: ReactNode;
}

const HEADER = {
  padding: '10px 12px',
  fontSize: 12,
  lineHeight: '16px',
  fontWeight: 600,
  letterSpacing: '0.06em',
  textTransform: 'uppercase',
  color: 'var(--vs-ink-muted)',
} as const;

/**
 * A staff table (design-system README, staff screens; mockup WarehouseStock): full width,
 * `small` text, `label` headers on `surface-sunken`, `line` hairlines between rows.
 */
export function StaffTable({ label, columns, minWidth, children }: StaffTableProps) {
  return (
    <Table.ScrollContainer
      minWidth={minWidth}
      type="native"
      className="vs-raised"
      style={{ borderRadius: 6 }}
    >
      <Table
        aria-label={label}
        verticalSpacing={12}
        horizontalSpacing={12}
        fz={13}
        lh="18px"
        borderColor="var(--vs-line)"
      >
        <Table.Thead className="vs-sunken">
          <Table.Tr>
            {columns.map((column) => (
              <Table.Th key={column.label} style={{ ...HEADER, textAlign: column.align ?? 'left' }}>
                {column.hidden ? <VisuallyHidden>{column.label}</VisuallyHidden> : column.label}
              </Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>{children}</Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
