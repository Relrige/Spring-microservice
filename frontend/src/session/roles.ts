// Role areas and landing pages (spec app-navigation, design D6).
import type { Role } from '../api/auth';

export const ROLE_LABELS: Record<Role, string> = {
  CUSTOMER: 'Customer',
  CATALOG_MANAGER: 'Catalog manager',
  INVENTORY_WORKER: 'Inventory worker',
  ADMIN: 'Admin',
};

/** URL prefix of each protected area and the one role that may open it (roles are not hierarchical). */
export const AREAS: { prefix: string; role: Role }[] = [
  { prefix: '/account', role: 'CUSTOMER' },
  { prefix: '/checkout', role: 'CUSTOMER' },
  { prefix: '/manage', role: 'CATALOG_MANAGER' },
  { prefix: '/warehouse', role: 'INVENTORY_WORKER' },
  { prefix: '/admin', role: 'ADMIN' },
];

export function roleHome(role: Role): string {
  switch (role) {
    case 'CUSTOMER':
      return '/';
    case 'CATALOG_MANAGER':
      return '/manage';
    case 'INVENTORY_WORKER':
      return '/warehouse';
    case 'ADMIN':
      return '/admin';
  }
}

function pathOf(url: string): string {
  return url.split(/[?#]/)[0] ?? url;
}

/** The role a path requires, or undefined for the open storefront. */
export function requiredRole(url: string): Role | undefined {
  const path = pathOf(url);
  return AREAS.find((area) => path === area.prefix || path.startsWith(`${area.prefix}/`))?.role;
}

export function isProtectedPath(url: string): boolean {
  return requiredRole(url) !== undefined;
}

/** Accepts only same-app relative paths, so `returnTo` cannot redirect elsewhere. */
export function safeReturnTo(value: string | null | undefined): string | null {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return null;
  }
  try {
    const url = new URL(value, 'http://app.invalid');
    if (url.origin !== 'http://app.invalid') return null;
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return null;
  }
}

/**
 * Where to go after login: the kept return page if this role may open it,
 * otherwise the role's landing area (spec app-navigation, Return page not allowed for the role).
 */
export function landingAfterLogin(role: Role, returnTo: string | null | undefined): string {
  const safe = safeReturnTo(returnTo);
  if (!safe) return roleHome(role);
  const path = pathOf(safe);
  if (path === '/login' || path === '/register') return roleHome(role);
  // The cart is open to visitors but is a customer page after login (change customer-cart, design D7).
  if (path === '/cart' && role !== 'CUSTOMER') return roleHome(role);
  const required = requiredRole(safe);
  if (required === undefined || required === role) return safe;
  return roleHome(role);
}

export function loginUrl(returnTo: string, expired = false): string {
  const params = new URLSearchParams();
  if (expired) params.set('expired', '1');
  if (returnTo && returnTo !== '/') params.set('returnTo', returnTo);
  const query = params.toString();
  return `/login${query ? `?${query}` : ''}`;
}
