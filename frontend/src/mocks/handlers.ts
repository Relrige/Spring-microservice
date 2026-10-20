// MSW handlers of the mock backend (design D10). Paths are the gateway's paths behind /api.
// Each handler follows the spec section named next to it; status codes and problem
// details match ../docs/design/services-specs/.
import { http, HttpResponse, type HttpHandler } from 'msw';
import type { Role } from '../api/auth';
import type { ProductStatus } from '../api/catalog';
import { db, type MockUser } from './db';
import { createMockToken, readMockToken, type MockClaims } from './tokens';

const api = (path: string) => `*/api${path}`;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const KINDS: Record<number, [string, string]> = {
  400: ['validation', 'Bad Request'],
  401: ['unauthorized', 'Unauthorized'],
  403: ['forbidden', 'Forbidden'],
  404: ['not-found', 'Not Found'],
  409: ['conflict', 'Conflict'],
  422: ['unprocessable', 'Unprocessable Entity'],
};

export function problem(
  status: number,
  detail: string,
  extra: Record<string, unknown> = {},
  service = 'mock',
) {
  const [kind, title] = KINDS[status] ?? ['internal-error', 'Internal Server Error'];
  return HttpResponse.json(
    {
      type: `https://voltstore.com/errors/${kind}`,
      title,
      status,
      detail,
      timestamp: new Date().toISOString(),
      service,
      ...extra,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

type AuthMode = 'public' | 'optional' | { required: Role[] };

/** Gateway request pipeline step 3–4: who is calling, or the 401/403 answer. */
function authenticate(
  request: Request,
  mode: AuthMode,
): { claims?: MockClaims; denied?: Response } {
  if (mode === 'public') return {};
  const header = request.headers.get('Authorization');
  const token = header?.match(/^Bearer (\S+)$/i)?.[1];
  const claims = token ? readMockToken(token) : undefined;
  if (mode === 'optional') {
    if (header && !claims)
      return { denied: problem(401, 'Authentication required', {}, 'api-gateway') };
    return { claims };
  }
  if (!claims) return { denied: problem(401, 'Authentication required', {}, 'api-gateway') };
  if (!mode.required.includes(claims.role)) {
    return { denied: problem(403, 'Access denied', {}, 'api-gateway') };
  }
  return { claims };
}

async function readJson(request: Request): Promise<Record<string, unknown> | undefined> {
  try {
    const body: unknown = await request.json();
    return body && typeof body === 'object' ? (body as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}

function credentialErrors(body: Record<string, unknown> | undefined): Record<string, string[]> {
  const errors: Record<string, string[]> = {};
  const email = typeof body?.email === 'string' ? body.email.trim() : '';
  const password = typeof body?.password === 'string' ? body.password : '';
  if (!email) errors.email = ['must not be blank'];
  else if (!EMAIL.test(email)) errors.email = ['must be a well-formed email address'];
  else if (email.length > 256) errors.email = ['size must be between 0 and 256'];
  if (!password.trim()) errors.password = ['must not be blank'];
  else if (password.length < 5 || password.length > 64) {
    errors.password = ['size must be between 5 and 64'];
  }
  return errors;
}

function createUser(email: string, password: string, role: Role): MockUser | undefined {
  const normalized = email.trim().toLowerCase();
  if (db.users.some((user) => user.email === normalized)) return undefined;
  const user: MockUser = { id: crypto.randomUUID(), email: normalized, password, role };
  db.users.push(user);
  return user;
}

function intParam(value: string | null, fallback: number): number | undefined {
  if (value === null) return fallback;
  return /^\d+$/.test(value) ? Number(value) : undefined;
}

function priceParam(value: string | null): number | null | undefined {
  if (value === null || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : undefined;
}

export const handlers: HttpHandler[] = [
  // Auth spec EP-AUTH-01: register a customer.
  http.post(api('/user/register'), async ({ request }) => {
    const body = await readJson(request);
    if (!body) return problem(400, 'Malformed JSON request');
    const errors = credentialErrors(body);
    if (Object.keys(errors).length > 0) return problem(400, 'Validation failed', { errors });
    const user = createUser(String(body.email), String(body.password), 'CUSTOMER');
    if (!user) return problem(409, 'Email already in use', {}, 'auth-service');
    return HttpResponse.json({ userId: user.id }, { status: 201 });
  }),

  // Auth spec EP-AUTH-02: log in.
  http.post(api('/auth/login'), async ({ request }) => {
    const body = await readJson(request);
    const errors: Record<string, string[]> = {};
    if (typeof body?.email !== 'string' || !body.email.trim()) errors.email = ['must not be blank'];
    if (typeof body?.password !== 'string' || !body.password.trim()) {
      errors.password = ['must not be blank'];
    }
    if (Object.keys(errors).length > 0) return problem(400, 'Validation failed', { errors });
    const email = String(body?.email).trim().toLowerCase();
    const user = db.users.find((u) => u.email === email && u.password === body?.password);
    if (!user) return problem(401, 'Invalid credentials', {}, 'auth-service');
    return HttpResponse.json({ token: createMockToken(user) });
  }),

  // Auth spec EP-AUTH-03: create a staff or admin account (gateway: Required, ADMIN).
  http.post(api('/user/non-customer'), async ({ request }) => {
    const { denied } = authenticate(request, { required: ['ADMIN'] });
    if (denied) return denied;
    const body = await readJson(request);
    const errors = credentialErrors(body);
    const role = body?.role;
    if (role === 'CUSTOMER') errors.role = ['must not be CUSTOMER'];
    else if (!['CATALOG_MANAGER', 'INVENTORY_WORKER', 'ADMIN'].includes(String(role))) {
      errors.role = ['must be a known role'];
    }
    if (Object.keys(errors).length > 0) return problem(400, 'Validation failed', { errors });
    const user = createUser(String(body?.email), String(body?.password), role as Role);
    if (!user) return problem(409, 'Email already in use', {}, 'auth-service');
    return HttpResponse.json({ userId: user.id }, { status: 201 });
  }),

  // Catalog spec EP-CAT-04: browse products (gateway: Optional).
  http.get(api('/catalog/products'), ({ request }) => {
    const { claims, denied } = authenticate(request, 'optional');
    if (denied) return denied;
    const params = new URL(request.url).searchParams;
    const errors: Record<string, string[]> = {};

    const page = intParam(params.get('page'), 0);
    const size = intParam(params.get('size'), 20);
    if (page === undefined) errors.page = ['must be a non-negative integer'];
    if (size === undefined || size < 1 || size > 100) errors.size = ['must be between 1 and 100'];
    const minPrice = priceParam(params.get('minPrice'));
    const maxPrice = priceParam(params.get('maxPrice'));
    if (minPrice === undefined) errors.minPrice = ['must be a number'];
    if (maxPrice === undefined) errors.maxPrice = ['must be a number'];
    const sortBy = params.get('sortBy') ?? 'title';
    const sortMatch = /^(title|basePrice)(?:,(asc|desc))?$/i.exec(sortBy);
    if (!sortMatch) errors.sortBy = ['must be title or basePrice, optionally with ,asc or ,desc'];
    const status = params.get('status');
    if (status !== null && status !== 'ACTIVE' && status !== 'NOT_ACTIVE') {
      errors.status = ['must be ACTIVE or NOT_ACTIVE'];
    }
    if (Object.keys(errors).length > 0)
      return problem(400, 'Invalid request parameters', { errors });
    if (typeof minPrice === 'number' && typeof maxPrice === 'number' && minPrice > maxPrice) {
      return problem(400, 'minPrice must not be greater than maxPrice', {}, 'catalog-service');
    }

    const isManager = claims?.role === 'CATALOG_MANAGER';
    const search = params.get('search')?.trim().toLowerCase();
    const categoryId = params.get('categoryId');
    const field = sortMatch![1] as 'title' | 'basePrice';
    const direction = sortMatch![2]?.toLowerCase() === 'desc' ? -1 : 1;

    const matches = db.products
      .filter((p) =>
        isManager ? !status || p.status === (status as ProductStatus) : p.status === 'ACTIVE',
      )
      .filter(
        (p) =>
          !search ||
          p.title.toLowerCase().includes(search) ||
          p.description.toLowerCase().includes(search),
      )
      .filter((p) => !categoryId || p.categoryId === categoryId)
      .filter((p) => typeof minPrice !== 'number' || p.basePrice >= minPrice)
      .filter((p) => typeof maxPrice !== 'number' || p.basePrice <= maxPrice)
      .sort((a, b) => {
        const order =
          field === 'title' ? a.title.localeCompare(b.title) : a.basePrice - b.basePrice;
        return order * direction || a.productId.localeCompare(b.productId);
      });

    const start = page! * size!;
    return HttpResponse.json({
      content: matches.slice(start, start + size!).map((p) => ({
        productId: p.productId,
        title: p.title,
        basePrice: p.basePrice,
        categoryId: p.categoryId,
        status: p.status,
        stockStatus: p.stockStatus,
      })),
      page: page!,
      totalElements: matches.length,
    });
  }),

  // Catalog spec EP-CAT-05: product details (gateway: Public; non-UUID ids have no route).
  http.get(api('/catalog/products/:productId'), ({ params }) => {
    const id = String(params.productId);
    if (!UUID.test(id)) return problem(404, 'No route matches the request.', {}, 'api-gateway');
    const product = db.products.find((p) => p.productId === id && p.status === 'ACTIVE');
    if (!product) return problem(404, 'Product not found', {}, 'catalog-service');
    return HttpResponse.json({
      productId: product.productId,
      title: product.title,
      description: product.description,
      categoryId: product.categoryId,
      basePrice: product.basePrice,
      stockStatus: product.stockStatus,
    });
  }),

  // Catalog spec EP-CAT-10: categories ordered by name (gateway: Public).
  http.get(api('/catalog/categories'), ({ request }) => {
    const params = new URL(request.url).searchParams;
    const page = intParam(params.get('page'), 0);
    const size = intParam(params.get('size'), 20);
    if (page === undefined || size === undefined || size < 1 || size > 100) {
      return problem(400, 'Invalid request parameters');
    }
    const search = params.get('search')?.toLowerCase();
    const matches = db.categories
      .filter((c) => !search || c.name.toLowerCase().includes(search))
      .sort((a, b) => a.name.localeCompare(b.name) || a.categoryId.localeCompare(b.categoryId));
    return HttpResponse.json({
      content: matches.slice(page * size, page * size + size),
      page,
      totalElements: matches.length,
    });
  }),

  // Gateway: anything else has no route.
  http.all(api('/*'), () => problem(404, 'No route matches the request.', {}, 'api-gateway')),
];
