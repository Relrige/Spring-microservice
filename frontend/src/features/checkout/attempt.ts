// One Idempotency-Key per checkout attempt (BL-07; change customer-checkout-orders, design D6).

export interface Attempt {
  /** The cart lines and the normalized delivery details the key was made for. */
  fingerprint: string;
  key: string;
}

/** Reuses the key while the submitted data stay the same; otherwise starts a new attempt. */
export function attemptFor(
  previous: Attempt | null,
  fingerprint: string,
  newKey: () => string = () => crypto.randomUUID(),
): Attempt {
  return previous?.fingerprint === fingerprint ? previous : { fingerprint, key: newKey() };
}

/** "Olena Kovalenko" → first "Olena", last "Kovalenko"; the service needs a non-blank last name. */
export function splitRecipient(name: string): { firstName: string; lastName: string } {
  const trimmed = name.trim();
  const space = trimmed.indexOf(' ');
  return space < 0
    ? { firstName: trimmed, lastName: '-' }
    : { firstName: trimmed.slice(0, space), lastName: trimmed.slice(space + 1).trim() };
}
