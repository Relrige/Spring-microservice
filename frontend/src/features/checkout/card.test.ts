import { describe, expect, it } from 'vitest';
import { cardNumberError, cvvError, expiryError, parseExpiry, toCardDetails } from './card';

describe('card rules', () => {
  const october2026 = new Date(2026, 9, 22);

  it('parses MM/YY and rejects other formats and months', () => {
    expect(parseExpiry('08/28')).toEqual({ month: 8, year: 2028 });
    expect(parseExpiry(' 8 / 28 ')).toEqual({ month: 8, year: 2028 });
    expect(parseExpiry('13/28')).toBeUndefined();
    expect(parseExpiry('0828')).toBeUndefined();
  });

  it('accepts the current month and rejects an earlier one', () => {
    expect(expiryError('10/26', october2026)).toBeNull();
    expect(expiryError('09/26', october2026)).toBe('This card has expired');
    expect(expiryError('1/2026', october2026)).toBe('Use the format MM/YY');
  });

  it('checks the number and the CVV, and sends the number without spaces', () => {
    expect(cardNumberError('4242 4242 4242 4242')).toBeNull();
    expect(cardNumberError('4242')).not.toBeNull();
    expect(cvvError('123')).toBeNull();
    expect(cvvError('12a')).not.toBeNull();
    expect(toCardDetails({ number: '4242 4242 4242 4242', expiry: '08/28', cvv: '123' })).toEqual({
      number: '4242424242424242',
      expiryMonth: 8,
      expiryYear: 2028,
      cvv: '123',
    });
  });
});
