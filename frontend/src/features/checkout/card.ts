// Card rules of Payment's EP-PAY-01, checked before EP-ORD-03 (change customer-checkout-orders, design D7).
import type { CardDetails } from '../../api/order';

const digits = (value: string) => value.replace(/\s/g, '');

/** "08/28" → August 2028; `undefined` when not MM/YY. */
export function parseExpiry(value: string): { month: number; year: number } | undefined {
  const match = /^\s*(\d{1,2})\s*\/\s*(\d{2})\s*$/.exec(value);
  if (!match) return undefined;
  const month = Number(match[1]);
  return month >= 1 && month <= 12 ? { month, year: 2000 + Number(match[2]) } : undefined;
}

export function cardNumberError(value: string): string | null {
  return /^\d{13,19}$/.test(digits(value)) ? null : 'Enter the 13 to 19 digits of the card number';
}

/** A card is valid until the end of its expiry month (local time). */
export function expiryError(value: string, now: Date = new Date()): string | null {
  const expiry = parseExpiry(value);
  if (!expiry) return 'Use the format MM/YY';
  const current = now.getFullYear() * 12 + now.getMonth() + 1;
  return expiry.year * 12 + expiry.month < current ? 'This card has expired' : null;
}

export function cvvError(value: string): string | null {
  return /^\d{3,4}$/.test(value.trim()) ? null : 'Enter the 3 or 4 digit code';
}

export function toCardDetails(values: {
  number: string;
  expiry: string;
  cvv: string;
}): CardDetails {
  const { month, year } = parseExpiry(values.expiry)!;
  return {
    number: digits(values.number),
    expiryMonth: month,
    expiryYear: year,
    cvv: values.cvv.trim(),
  };
}
