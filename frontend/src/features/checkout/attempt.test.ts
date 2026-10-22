import { describe, expect, it } from 'vitest';
import { attemptFor, splitRecipient } from './attempt';

describe('checkout attempt key', () => {
  it('reuses the key for the same data and renews it when the data change', () => {
    let n = 0;
    const newKey = () => `key-${++n}`;
    const first = attemptFor(null, 'a', newKey);
    expect(first).toEqual({ fingerprint: 'a', key: 'key-1' });
    expect(attemptFor(first, 'a', newKey)).toBe(first);
    expect(attemptFor(first, 'b', newKey)).toEqual({ fingerprint: 'b', key: 'key-2' });
  });

  it('splits the recipient into first and last name at the first space', () => {
    expect(splitRecipient(' Olena  Kovalenko ')).toEqual({
      firstName: 'Olena',
      lastName: 'Kovalenko',
    });
    expect(splitRecipient('Anna Maria Shevchenko')).toEqual({
      firstName: 'Anna',
      lastName: 'Maria Shevchenko',
    });
    expect(splitRecipient('Cher')).toEqual({ firstName: 'Cher', lastName: '-' });
  });
});
