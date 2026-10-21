// Auth Service calls, mirroring ../docs/design/services-specs/auth-service-spec.md.
import { request } from './http';

/** Spec "Service Overview": `UserRole`. */
export const ROLES = ['CUSTOMER', 'CATALOG_MANAGER', 'INVENTORY_WORKER', 'ADMIN'] as const;
export type Role = (typeof ROLES)[number];

export interface Credentials {
  email: string;
  password: string;
}

/** EP-AUTH-01 and EP-AUTH-03: `201 Created { userId }`. */
export interface CreatedUser {
  userId: string;
}

/** EP-AUTH-02: `200 OK { token }`. */
export interface LoginResponse {
  token: string;
}

/** EP-AUTH-03 body: `{ email, password, role }`, role not `CUSTOMER`. */
export interface NonCustomerRequest extends Credentials {
  role: Exclude<Role, 'CUSTOMER'>;
}

/** EP-AUTH-01: register a customer. `409` = email in use, `400` = field errors. */
export function register(body: Credentials): Promise<CreatedUser> {
  return request<CreatedUser>('/user/register', { method: 'POST', body, auth: 'none' });
}

/**
 * EP-AUTH-02: log in. `401` = invalid credentials. Sends no token, so its
 * `401` never counts as an expired session (spec api-access).
 */
export function login(body: Credentials): Promise<LoginResponse> {
  return request<LoginResponse>('/auth/login', { method: 'POST', body, auth: 'none' });
}

/** EP-AUTH-03: create a staff or admin account (admin only). */
export function createNonCustomer(
  body: NonCustomerRequest,
  auth: { token: string } | 'session' = 'session',
): Promise<CreatedUser> {
  return request<CreatedUser>('/user/non-customer', { method: 'POST', body, auth });
}
