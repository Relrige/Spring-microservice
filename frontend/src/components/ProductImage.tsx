// BL-06: Catalog has no product images yet; every product shows a placeholder per
// category (design D15, design-system README "Product images"). When imageUrl
// exists, show it here and keep the placeholder as the fallback.
import {
  IconBox,
  IconDeviceLaptop,
  IconDeviceMobile,
  IconDevices,
  IconHeadphones,
  IconPlug,
  type Icon,
} from '@tabler/icons-react';

interface Slot {
  ground: string;
  ink: string;
}

const SLOTS: Slot[] = [
  { ground: 'var(--vs-art-blue)', ink: 'var(--vs-primary)' },
  { ground: 'var(--vs-art-yellow)', ink: 'var(--vs-art-yellow-ink)' },
  { ground: 'var(--vs-art-mint)', ink: 'var(--vs-success)' },
  { ground: 'var(--vs-art-coral)', ink: 'var(--vs-art-coral-ink)' },
];

const NEUTRAL: Slot = { ground: 'var(--vs-surface-sunken)', ink: 'var(--vs-ink-muted)' };

const KNOWN: { kind: string; match: RegExp; slot: number; icon: Icon }[] = [
  { kind: 'phones', match: /phone|smartphone|mobile/i, slot: 0, icon: IconDeviceMobile },
  { kind: 'laptops', match: /laptop|notebook/i, slot: 1, icon: IconDeviceLaptop },
  { kind: 'audio', match: /audio|headphone|speaker|sound/i, slot: 2, icon: IconHeadphones },
  { kind: 'accessories', match: /accessor|charger|cable/i, slot: 3, icon: IconPlug },
];

function stableHash(text: string): number {
  let hash = 0;
  for (const char of text.toLowerCase()) hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  return hash;
}

export interface Placeholder {
  /** A known category kind, `other:<slot>` for other names, `unknown` without a category. */
  kind: string;
  slot: Slot;
  icon: Icon;
}

/** The placeholder of a category name; `null`/`undefined` = category unknown. */
export function placeholderFor(categoryName: string | null | undefined): Placeholder {
  if (!categoryName) return { kind: 'unknown', slot: NEUTRAL, icon: IconBox };
  const known = KNOWN.find((entry) => entry.match.test(categoryName));
  if (known) return { kind: known.kind, slot: SLOTS[known.slot]!, icon: known.icon };
  const index = stableHash(categoryName) % SLOTS.length;
  return { kind: `other:${index}`, slot: SLOTS[index]!, icon: IconDevices };
}

interface ProductImageProps {
  categoryName: string | null | undefined;
  /** CSS aspect ratio: 4:3 on tiles and the product page, 1:1 in rows. */
  ratio?: string;
  radius?: number;
  /** Fixed size in px (rows); otherwise fills the width. */
  size?: number;
}

export function ProductImage({
  categoryName,
  ratio = '4 / 3',
  radius = 10,
  size,
}: ProductImageProps) {
  const { kind, slot, icon: CategoryIcon } = placeholderFor(categoryName);
  return (
    <div
      role="img"
      aria-label={categoryName ? `${categoryName} placeholder image` : 'Product placeholder image'}
      data-placeholder={kind}
      style={{
        width: size ?? '100%',
        height: size,
        flex: size ? `0 0 ${size}px` : undefined,
        aspectRatio: size ? undefined : ratio,
        borderRadius: radius,
        background: slot.ground,
        color: slot.ink,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        containerType: 'size',
      }}
    >
      <CategoryIcon stroke={1.5} style={{ width: '40cqh', height: '40cqh' }} aria-hidden />
    </div>
  );
}
