// The one request function of the app (design D4, spec api-access).
import { ApiError, kindForStatus, parseProblem, parseRetryAfter } from './errors';

export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** Sent as JSON. */
  body?: unknown;
  /** Query parameters; null, undefined and empty strings are left out. */
  query?: Record<string, QueryValue>;
  /**
   * `session` (default): send the session token when there is one.
   * `none`: never send a token (storefront catalog calls, login, register).
   * `{ token }`: send this token (seed script).
   */
  auth?: 'session' | 'none' | { token: string };
  /** Extra request headers, e.g. `Idempotency-Key`; never `Authorization`. */
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

interface HttpConfig {
  /** Prefix of every request: `/api` on the app's own origin (design D2). */
  baseUrl: string;
  getToken: () => string | null;
  /** Called when a request that carried the session token is answered 401. */
  onSessionExpired: () => void;
}

const config: HttpConfig = {
  baseUrl: defaultBaseUrl(),
  getToken: () => null,
  onSessionExpired: () => {},
};

function defaultBaseUrl(): string {
  return typeof window !== 'undefined' ? `${window.location.origin}/api` : '/api';
}

export function configureHttp(changes: Partial<HttpConfig>): void {
  Object.assign(config, changes);
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === null || value === undefined || value === '') continue;
    params.append(key, String(value));
  }
  const search = params.toString();
  return `${config.baseUrl}${path}${search ? `?${search}` : ''}`;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, auth = 'session', signal } = options;

  const headers: Record<string, string> = {
    ...options.headers,
    Accept: 'application/json, application/problem+json',
  };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let sessionToken: string | null = null;
  if (auth === 'session') {
    sessionToken = config.getToken();
    if (sessionToken) headers.Authorization = `Bearer ${sessionToken}`;
  } else if (typeof auth === 'object') {
    headers.Authorization = `Bearer ${auth.token}`;
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    if (signal?.aborted) throw cause;
    throw new ApiError('network');
  }

  const text = await response.text().catch(() => '');

  if (!response.ok) {
    // Spec api-access, Expired or rejected session: only a request that
    // carried the session token ends the session (login sends none).
    if (response.status === 401 && sessionToken) config.onSessionExpired();
    throw new ApiError(kindForStatus(response.status), {
      status: response.status,
      problem: parseProblem(text),
      retryAfter: parseRetryAfter(response.headers.get('Retry-After')),
    });
  }

  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError('unexpected', { status: response.status });
  }
}
