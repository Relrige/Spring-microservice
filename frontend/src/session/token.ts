// Identity from the session token (spec user-session, design D5).
// The payload is decoded without verifying the signature: the gateway verifies it.
import { ROLES, type Role } from '../api/auth';

export interface Session {
  token: string;
  userId: string;
  role: Role;
  email?: string;
  /** Epoch milliseconds, from `exp`. */
  expiresAt: number;
}

export type DecodeResult =
  { ok: true; session: Session } | { ok: false; reason: 'unreadable' | 'unknown-role' | 'expired' };

function decodeBase64Url(part: string): string {
  const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value);
}

export function decodeToken(token: string, now: number = Date.now()): DecodeResult {
  const parts = token.split('.');
  if (parts.length !== 3 || !parts[1]) return { ok: false, reason: 'unreadable' };
  let claims: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(decodeBase64Url(parts[1]));
    if (!parsed || typeof parsed !== 'object') return { ok: false, reason: 'unreadable' };
    claims = parsed as Record<string, unknown>;
  } catch {
    return { ok: false, reason: 'unreadable' };
  }
  if (typeof claims.sub !== 'string' || !claims.sub || typeof claims.exp !== 'number') {
    return { ok: false, reason: 'unreadable' };
  }
  if (!isRole(claims.role)) return { ok: false, reason: 'unknown-role' };
  const expiresAt = claims.exp * 1000;
  if (expiresAt <= now) return { ok: false, reason: 'expired' };
  return {
    ok: true,
    session: {
      token,
      userId: claims.sub,
      role: claims.role,
      ...(typeof claims.email === 'string' && claims.email ? { email: claims.email } : {}),
      expiresAt,
    },
  };
}
