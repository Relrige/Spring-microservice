import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { server } from '../test/server';
import { ApiError, MESSAGES, userMessage } from './errors';
import { configureHttp, request } from './http';

const URL = '*/api/catalog/products';

function problem(status: number, extra: Record<string, unknown> = {}) {
  return HttpResponse.json(
    { type: 'https://voltstore.com/errors/x', title: 'T', status, ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('expected the request to fail');
}

afterEach(() => configureHttp({ getToken: () => null, onSessionExpired: () => {} }));

describe('request', () => {
  it('sends requests under the /api prefix of the own origin', async () => {
    let seen = '';
    server.use(
      http.get(URL, ({ request }) => {
        seen = request.url;
        return HttpResponse.json({ ok: true });
      }),
    );
    await request('/catalog/products', { query: { page: 0, search: '', categoryId: undefined } });
    expect(seen).toBe(`${window.location.origin}/api/catalog/products?page=0`);
  });

  it('sends the session token as a bearer header and none when opted out', async () => {
    const headers: (string | null)[] = [];
    server.use(
      http.get(URL, ({ request }) => {
        headers.push(request.headers.get('Authorization'));
        return HttpResponse.json({});
      }),
    );
    configureHttp({ getToken: () => 'abc' });
    await request('/catalog/products');
    await request('/catalog/products', { auth: 'none' });
    configureHttp({ getToken: () => null });
    await request('/catalog/products');
    expect(headers).toEqual(['Bearer abc', null, null]);
  });

  it('sends JSON bodies and returns undefined for 204', async () => {
    let body: unknown;
    server.use(
      http.post('*/api/x', async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await expect(request('/x', { method: 'POST', body: { a: 1 } })).resolves.toBeUndefined();
    expect(body).toEqual({ a: 1 });
  });

  it.each([
    [400, 'validation'],
    [401, 'unauthorized'],
    [402, 'payment'],
    [403, 'forbidden'],
    [404, 'not-found'],
    [409, 'conflict'],
    [422, 'unprocessable'],
    [500, 'unexpected'],
    [502, 'unavailable'],
    [503, 'unavailable'],
    [504, 'unavailable'],
  ] as const)('maps status %i to kind %s', async (status, kind) => {
    server.use(http.get(URL, () => problem(status, { detail: 'D' })));
    const error = await failure(request('/catalog/products'));
    expect(error.kind).toBe(kind);
    expect(error.status).toBe(status);
    expect(error.problem?.detail).toBe('D');
  });

  it('reads problem-detail extensions such as field errors', async () => {
    server.use(
      http.get(URL, () =>
        problem(400, { errors: { email: ['must be a well-formed email address'] } }),
      ),
    );
    const error = await failure(request('/catalog/products'));
    expect(error.problem?.errors).toEqual({ email: ['must be a well-formed email address'] });
  });

  it('shows the detail of a conflict', async () => {
    server.use(http.get(URL, () => problem(409, { detail: 'Category name already in use' })));
    expect(userMessage(await failure(request('/catalog/products')))).toBe(
      'Category name already in use',
    );
  });

  it('handles a body that is not JSON by status alone', async () => {
    server.use(http.get(URL, () => new HttpResponse('<html>Bad gateway</html>', { status: 502 })));
    const error = await failure(request('/catalog/products'));
    expect(error.kind).toBe('unavailable');
    expect(error.problem).toBeUndefined();
    expect(userMessage(error)).toBe(`${MESSAGES.unavailable} Please try again in a moment.`);
  });

  it('handles an empty error body', async () => {
    server.use(http.get(URL, () => new HttpResponse(null, { status: 404 })));
    const error = await failure(request('/catalog/products'));
    expect(error.kind).toBe('not-found');
    expect(userMessage(error)).toBe(MESSAGES.notFound);
  });

  it('reads Retry-After on 503 and mentions it', async () => {
    server.use(
      http.get(
        URL,
        () => new HttpResponse(null, { status: 503, headers: { 'Retry-After': '10' } }),
      ),
    );
    const error = await failure(request('/catalog/products'));
    expect(error.retryAfter).toBe(10);
    expect(userMessage(error)).toBe(`${MESSAGES.unavailable} Try again in 10 seconds.`);
  });

  it('turns a network failure into a network error', async () => {
    server.use(http.get(URL, () => HttpResponse.error()));
    const error = await failure(request('/catalog/products'));
    expect(error.kind).toBe('network');
    expect(userMessage(error)).toBe(MESSAGES.network);
  });

  it('never shows technical details of a 500', async () => {
    server.use(
      http.get(URL, () => problem(500, { detail: 'NullPointerException at Foo.java:12' })),
    );
    expect(userMessage(await failure(request('/catalog/products')))).toBe(MESSAGES.unexpected);
  });

  it('shows the access message for 403', async () => {
    server.use(http.get(URL, () => problem(403, { detail: 'Access denied' })));
    expect(userMessage(await failure(request('/catalog/products')))).toBe(
      'You do not have access to this action.',
    );
  });
});

describe('session-expired signal', () => {
  it('fires when a request that carried the token is answered 401', async () => {
    const expired = vi.fn();
    configureHttp({ getToken: () => 'old', onSessionExpired: expired });
    server.use(http.get(URL, () => problem(401)));
    await failure(request('/catalog/products'));
    expect(expired).toHaveBeenCalledTimes(1);
  });

  it('does not fire for the login call, which sends no token', async () => {
    const expired = vi.fn();
    configureHttp({ getToken: () => 'old', onSessionExpired: expired });
    server.use(
      http.post('*/api/auth/login', () => problem(401, { detail: 'Invalid credentials' })),
    );
    const error = await failure(
      request('/auth/login', { method: 'POST', auth: 'none', body: { email: 'a', password: 'b' } }),
    );
    expect(error.kind).toBe('unauthorized');
    expect(expired).not.toHaveBeenCalled();
  });

  it('does not fire for an anonymous request', async () => {
    const expired = vi.fn();
    configureHttp({ onSessionExpired: expired });
    server.use(http.get(URL, () => problem(401)));
    await failure(request('/catalog/products'));
    expect(expired).not.toHaveBeenCalled();
  });
});
