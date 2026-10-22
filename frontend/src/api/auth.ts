// Auth Service calls: spec ../docs/design/services-specs/auth-service-spec.md,
// types generated from ../auth-service/openapi/public.json (AGENTS.md §3).
import { pathsOf, type JsonBody, type Ok, type Received } from './contract';
import type { components, operations, paths } from './generated/auth';
import { request } from './http';

type Schemas = components['schemas'];
const pathFor = pathsOf<paths>();

/** Spec "Service Overview": `UserRole`. A runtime list, pinned to the generated enum by a type test. */
export const ROLES = ['CUSTOMER', 'CATALOG_MANAGER', 'INVENTORY_WORKER', 'ADMIN'] as const;
export type Role = (typeof ROLES)[number];

export type Credentials = JsonBody<operations['registerUser']>;

/** EP-AUTH-01 and EP-AUTH-03: `201 Created { userId }`. */
export type CreatedUser = Received<Schemas['RegisterUserResponse']>;

/** EP-AUTH-02: `200 OK { token }`. */
export type LoginResponse = Received<Ok<operations['login']>>;

/** EP-AUTH-03 body. The document allows any role; the app never sends `CUSTOMER` (the service rejects it). */
export type NonCustomerRequest = Omit<JsonBody<operations['createNonCustomerUser']>, 'role'> & {
  role: Exclude<Role, 'CUSTOMER'>;
};

/** EP-AUTH-01 (`registerUser`): register a customer. `409` = email in use, `400` = field errors. */
export function register(body: Credentials): Promise<CreatedUser> {
  return request<CreatedUser>(pathFor('/user/register'), { method: 'POST', body, auth: 'none' });
}

/**
 * EP-AUTH-02 (`login`): log in. `401` = invalid credentials. Sends no token, so
 * its `401` never counts as an expired session (spec api-access).
 */
export function login(body: JsonBody<operations['login']>): Promise<LoginResponse> {
  return request<LoginResponse>(pathFor('/auth/login'), { method: 'POST', body, auth: 'none' });
}

/** EP-AUTH-03 (`createNonCustomerUser`): create a staff or admin account (admin only). */
export function createNonCustomer(
  body: NonCustomerRequest,
  auth: { token: string } | 'session' = 'session',
): Promise<CreatedUser> {
  return request<CreatedUser>(pathFor('/user/non-customer'), { method: 'POST', body, auth });
}
