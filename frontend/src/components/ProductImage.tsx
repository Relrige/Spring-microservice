// A product's image (Catalog imageUrl) on a white tile; without an image, or when
// it fails to load, a placeholder per category (design-system README "Product images";
// change product-images, design D2).
import { useState } from 'react';
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
  /** Catalog's `imageUrl`; missing or `null` = no image. */
  imageUrl?: string | null;
  /** CSS aspect ratio: 4:3 on tiles and the product page, 1:1 in rows. */
  ratio?: string;
  radius?: number;
  /** Fixed size in px (rows); otherwise fills the width. */
  size?: number;
}

export function ProductImage({
  categoryName,
  imageUrl,
  ratio = '4 / 3',
  radius = 10,
  size,
}: ProductImageProps) {
  // The URL that failed to load; a new URL is tried again.
  const [failed, setFailed] = useState<string | null>(null);
  const { kind, slot, icon: CategoryIcon } = placeholderFor(categoryName);
  const box = {
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
    overflow: 'hidden',
  } as const;

  if (imageUrl && imageUrl !== failed) {
    return (
      // Product shots come on white, so the box is white in both themes and shows the whole product.
      <div data-image={imageUrl} style={{ ...box, background: '#fff' }}>
        {/* Decorative: the title is always shown next to it. */}
        <img
          src={imageUrl}
          alt=""
          loading="lazy"
          onError={() => setFailed(imageUrl)}
          style={{ width: '100%', height: '100%', objectFit: 'contain', padding: '6%' }}
        />
      </div>
    );
  }
  return (
    <div
      role="img"
      aria-label={categoryName ? `${categoryName} placeholder image` : 'Product placeholder image'}
      data-placeholder={kind}
      style={box}
    >
      <CategoryIcon stroke={1.5} style={{ width: '40cqh', height: '40cqh' }} aria-hidden />
    </div>
  );
}
