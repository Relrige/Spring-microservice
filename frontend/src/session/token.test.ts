import { describe, expect, it } from 'vitest';
import { createMockToken } from '../mocks/tokens';
import { landingAfterLogin, safeReturnTo } from './roles';
import { decodeToken } from './token';

function rawToken(payload: unknown): string {
  const encode = (value: unknown) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${encode({ alg: 'HS256' })}.${encode(payload)}.signature`;
}

const future = Math.floor(Date.now() / 1000) + 3600;

describe('decodeToken', () => {
  it('reads id, role, email and expiry from a valid token', () => {
    const token = createMockToken({ id: 'u-1', role: 'CUSTOMER', email: 'olena@example.com' });
    const result = decodeToken(token);
    expect(result).toMatchObject({
      ok: true,
      session: { userId: 'u-1', role: 'CUSTOMER', email: 'olena@example.com', token },
    });
  });

  it('accepts a token without the email claim', () => {
    const result = decodeToken(rawToken({ sub: 'u-2', role: 'ADMIN', exp: future }));
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.session.email).toBeUndefined();
  });

  it('rejects an unknown role', () => {
    expect(decodeToken(rawToken({ sub: 'u', role: 'ROOT', exp: future }))).toEqual({
      ok: false,
      reason: 'unknown-role',
    });
  });

  it('rejects an unreadable payload', () => {
    expect(decodeToken('not.a-token!.x')).toEqual({ ok: false, reason: 'unreadable' });
    expect(decodeToken('abc')).toEqual({ ok: false, reason: 'unreadable' });
    expect(decodeToken(rawToken({ role: 'ADMIN', exp: future }))).toEqual({
      ok: false,
      reason: 'unreadable',
    });
  });

  it('rejects an expired token', () => {
    const past = Math.floor(Date.now() / 1000) - 10;
    expect(decodeToken(rawToken({ sub: 'u', role: 'ADMIN', exp: past }))).toEqual({
      ok: false,
      reason: 'expired',
    });
  });
});

describe('return pages', () => {
  it('rejects external and protocol-relative returnTo values', () => {
    expect(safeReturnTo('https://evil.example/x')).toBeNull();
    expect(safeReturnTo('//evil.example/x')).toBeNull();
    expect(safeReturnTo('/\\evil.example')).toBeNull();
    expect(safeReturnTo('javascript:alert(1)')).toBeNull();
    expect(safeReturnTo('/account/orders?x=1')).toBe('/account/orders?x=1');
  });

  it('uses the return page only when the role may open it', () => {
    expect(landingAfterLogin('CUSTOMER', '/account/orders')).toBe('/account/orders');
    expect(landingAfterLogin('CUSTOMER', '/warehouse')).toBe('/');
    expect(landingAfterLogin('INVENTORY_WORKER', null)).toBe('/warehouse');
    expect(landingAfterLogin('ADMIN', '/manage')).toBe('/admin');
    expect(landingAfterLogin('CATALOG_MANAGER', '/products/x?y=1')).toBe('/products/x?y=1');
    expect(landingAfterLogin('CATALOG_MANAGER', '/login')).toBe('/manage');
  });
});
