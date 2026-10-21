import { expect, type Page } from '@playwright/test';
import { DEMO_ADMIN, type DemoAccount } from '../src/fixtures/demo-data';

export const SESSION_KEY = 'voltstore.session';

/**
 * The admin's password: the mock uses the fixture value; a live stack uses
 * its ADMIN_PASSWORD, given to the tests as E2E_ADMIN_PASSWORD (or SEED_ADMIN_PASSWORD).
 */
export function adminAccount(): DemoAccount {
  const password = process.env.E2E_ADMIN_PASSWORD ?? process.env.SEED_ADMIN_PASSWORD;
  return password ? { ...DEMO_ADMIN, password } : DEMO_ADMIN;
}

export async function logIn(page: Page, email: string, password: string) {
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();
}

export async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

/** Replaces the stored token's expiry so the session ends in `seconds` (the app reads exp only). */
export async function shortenSession(page: Page, seconds: number) {
  await page.evaluate(
    ({ key, seconds }) => {
      const token = window.localStorage.getItem(key);
      if (!token) throw new Error('no session');
      const [header, payload] = token.split('.');
      const decode = (part: string) =>
        JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/'))) as Record<string, unknown>;
      const encode = (value: unknown) =>
        btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
      const claims = decode(payload!);
      claims.exp = Math.floor(Date.now() / 1000) + seconds;
      window.localStorage.setItem(key, `${header}.${encode(claims)}.unsigned`);
    },
    { key: SESSION_KEY, seconds },
  );
}
