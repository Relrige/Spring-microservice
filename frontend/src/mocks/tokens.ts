// Unsigned JWT-shaped tokens with the claims of the Auth spec ("JWT": sub, role, email, iat, exp).
import type { Role } from '../api/auth';

export interface MockClaims {
  sub: string;
  role: Role;
  email?: string;
  iat: number;
  exp: number;
}

function base64UrlEncode(text: string): string {
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64UrlDecode(part: string): string {
  const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
  return atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
}

/** Creates a mock token; `lifetimeSeconds` defaults to the real 1 hour. */
export function createMockToken(
  user: { id: string; role: Role; email?: string },
  options: { lifetimeSeconds?: number; now?: number } = {},
): string {
  const iat = Math.floor((options.now ?? Date.now()) / 1000);
  const claims: MockClaims = {
    sub: user.id,
    role: user.role,
    ...(user.email !== undefined ? { email: user.email } : {}),
    iat,
    exp: iat + (options.lifetimeSeconds ?? 3600),
  };
  const header = base64UrlEncode(JSON.stringify({ alg: 'none', typ: 'JWT' }));
  return `${header}.${base64UrlEncode(JSON.stringify(claims))}.mock-signature`;
}

/** Reads a mock token as the gateway would: undefined when malformed or expired. */
export function readMockToken(token: string, now: number = Date.now()): MockClaims | undefined {
  const payload = token.split('.')[1];
  if (!payload) return undefined;
  try {
    const claims = JSON.parse(base64UrlDecode(payload)) as MockClaims;
    if (typeof claims.exp !== 'number' || claims.exp * 1000 <= now) return undefined;
    return claims;
  } catch {
    return undefined;
  }
}
