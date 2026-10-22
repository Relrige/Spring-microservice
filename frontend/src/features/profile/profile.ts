// Profile rules and query shared by the profile page and the checkout (EP-CUST-01/02).
import { useQuery } from '@tanstack/react-query';
import { getProfile, type Profile } from '../../api/customer';

export const profileKey = ['profile'] as const;

/** EP-CUST-02 phone: `+` and 8–15 digits; spaces are removed before checking and sending. */
const PHONE = /^\+[1-9]\d{7,14}$/;
export const PHONE_HINT = 'Use the international format, for example +380501234567';

export const normalizePhone = (value: string) => value.replace(/\s/g, '');

export function phoneError(value: string): string | null {
  const phone = normalizePhone(value);
  if (!phone) return 'Enter a phone number';
  return PHONE.test(phone) ? null : PHONE_HINT;
}

/** A profile that was never saved: every field missing or `null`. */
export const isEmptyProfile = (profile: Profile | undefined) =>
  !profile || Object.values(profile).every((value) => value == null);

export function useProfile() {
  return useQuery({ queryKey: profileKey, queryFn: () => getProfile() });
}
