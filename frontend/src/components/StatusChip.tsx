import type { StockStatus } from '../api/catalog';

export type Tone = 'success' | 'warning' | 'danger' | 'primary' | 'neutral';

const TONES: Record<Tone, { background: string; color: string }> = {
  success: { background: 'var(--vs-success-soft)', color: 'var(--vs-success)' },
  warning: { background: 'var(--vs-warning-soft)', color: 'var(--vs-warning)' },
  danger: { background: 'var(--vs-danger-soft)', color: 'var(--vs-danger)' },
  primary: { background: 'var(--vs-primary-soft)', color: 'var(--vs-primary)' },
  neutral: { background: 'var(--vs-surface-sunken)', color: 'var(--vs-ink-muted)' },
};

/** A status chip: `-soft` ground, strong text, `label` style, always with a word. */
export function StatusChip({ tone, children }: { tone: Tone; children: string }) {
  return (
    <span
      style={{
        ...TONES[tone],
        display: 'inline-block',
        padding: '2px 8px',
        borderRadius: 6,
        fontSize: 12,
        lineHeight: '16px',
        fontWeight: 600,
        letterSpacing: '0.06em',
        textTransform: 'uppercase',
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </span>
  );
}

/** "In stock" / "Out of stock" from Catalog's read model (design-system README, status words). */
export function StockBadge({ status }: { status: StockStatus }) {
  return status === 'IN_STOCK' ? (
    <StatusChip tone="success">In stock</StatusChip>
  ) : (
    <StatusChip tone="danger">Out of stock</StatusChip>
  );
}
