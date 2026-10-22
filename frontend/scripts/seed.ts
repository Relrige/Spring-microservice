// Demo seed script (design D14): fills a running stack through the API Gateway with
// the shared demo fixtures, using the real flows so every event and outbox path runs.
// Safe to rerun: it only creates what is missing and adjusts stock to its target.
//
//   SEED_ADMIN_PASSWORD=<stack ADMIN_PASSWORD> npm run seed
//
// Variables: SEED_API_URL (default http://localhost:8080), SEED_ADMIN_EMAIL
// (default admin@voltstore.com), SEED_ADMIN_PASSWORD (required).
import { createNonCustomer, login, register, type Credentials } from '../src/api/auth';
import {
  createCategory,
  createProduct,
  listCategories,
  listProducts,
  setProductStatus,
  type Category,
  type ProductSummary,
} from '../src/api/catalog';
import { ApiError } from '../src/api/errors';
import { configureHttp } from '../src/api/http';
import { getCart, replaceCart } from '../src/api/customer';
import { adjustStock, listStock, setPackaging, type StockItem } from '../src/api/inventory';
import {
  DEMO_CART,
  DEMO_CATEGORIES,
  DEMO_CUSTOMER,
  DEMO_PRODUCTS,
  DEMO_STAFF,
  type DemoAccount,
  type DemoProduct,
} from '../src/fixtures/demo-data';

const API_URL = (process.env.SEED_API_URL ?? 'http://localhost:8080').replace(/\/$/, '');
const ADMIN_EMAIL = process.env.SEED_ADMIN_EMAIL ?? 'admin@voltstore.com';
const ADMIN_PASSWORD = process.env.SEED_ADMIN_PASSWORD;
const CONVERGE_SECONDS = 60;

type Token = { token: string };

const summary = {
  accountsCreated: [] as string[],
  categoriesCreated: 0,
  productsCreated: 0,
  packagingSet: 0,
  stockAdjusted: 0,
  activated: 0,
  deactivated: 0,
  demoCart: 'kept' as 'set' | 'kept',
};

class SeedError extends Error {
  constructor(
    message: string,
    readonly apiError?: ApiError,
  ) {
    super(message);
  }
}

function log(message: string) {
  console.log(message);
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    const detail = error.problem?.detail ? `: ${error.problem.detail.replace(/\.$/, '')}` : '';
    const reason = error.problem?.reason ? ` (reason ${String(error.problem.reason)})` : '';
    return `${error.status ?? 'no answer'} ${error.kind}${detail}${reason}`;
  }
  return error instanceof Error ? error.message : String(error);
}

/** Runs a step, retrying `503` (and network failures) with back-off or the Retry-After hint. */
async function withRetry<T>(what: string, action: () => Promise<T>, attempts = 5): Promise<T> {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return await action();
    } catch (error) {
      const transient =
        error instanceof ApiError &&
        (error.status === 503 || error.status === 502 || error.kind === 'network');
      if (!transient || attempt >= attempts) {
        throw new SeedError(
          `${what} failed: ${describeError(error)}`,
          error instanceof ApiError ? error : undefined,
        );
      }
      const waitSeconds =
        error instanceof ApiError && error.retryAfter !== undefined
          ? Math.min(error.retryAfter, 15)
          : 2 ** attempt;
      log(`  ${what}: ${describeError(error)}; retrying in ${waitSeconds} s`);
      await sleep(waitSeconds * 1000);
    }
  }
}

async function loginAs(account: Credentials, label: string): Promise<Token> {
  try {
    return await withRetry(`log in as ${label}`, () => login(account));
  } catch (error) {
    const status = error instanceof SeedError ? error.apiError?.status : undefined;
    const hint =
      status === undefined || status >= 500
        ? `Is the stack running and the gateway reachable at ${API_URL}?`
        : label === 'admin'
          ? 'Check SEED_ADMIN_EMAIL and SEED_ADMIN_PASSWORD (the stack’s ADMIN_EMAIL / ADMIN_PASSWORD).'
          : `The account ${account.email} exists with another password; reset it or remove it.`;
    throw new SeedError(`${(error as Error).message}. ${hint}`);
  }
}

/** Creates an account; `409` means it is already there. */
async function ensureAccount(account: DemoAccount, admin: Token) {
  try {
    await withRetry(`create ${account.email}`, () =>
      account.role === 'CUSTOMER'
        ? register(account)
        : createNonCustomer(
            { email: account.email, password: account.password, role: account.role },
            admin,
          ),
    );
    summary.accountsCreated.push(account.email);
    log(`  created ${account.email} (${account.label})`);
  } catch (error) {
    if (!(error instanceof SeedError) || error.apiError?.status !== 409) throw error;
    log(`  ${account.email} (${account.label}) already exists`);
  }
}

async function ensureCategories(manager: Token): Promise<Map<string, string>> {
  const ids = new Map<string, string>();
  for (const name of DEMO_CATEGORIES) {
    const found = await withRetry(`find category ${name}`, () =>
      listCategories({ search: name, size: 100 }, { auth: manager }),
    );
    let category: Category | undefined = found.content.find((c) => c.name === name);
    if (!category) {
      category = await withRetry(`create category ${name}`, () => createCategory(name, manager));
      summary.categoriesCreated += 1;
      log(`  created category ${name}`);
    }
    ids.set(name, category.categoryId);
  }
  return ids;
}

/** Every product the manager sees (active and inactive), by title. */
async function managerProducts(manager: Token): Promise<Map<string, ProductSummary>> {
  const byTitle = new Map<string, ProductSummary>();
  for (let page = 0; ; page += 1) {
    const result = await withRetry('list products as manager', () =>
      listProducts({ page, size: 100 }, { auth: manager }),
    );
    for (const product of result.content) byTitle.set(product.title, product);
    if ((page + 1) * 100 >= result.totalElements || result.content.length === 0) break;
  }
  return byTitle;
}

async function ensureProducts(
  manager: Token,
  categoryIds: Map<string, string>,
): Promise<Map<DemoProduct, ProductSummary>> {
  let existing = await managerProducts(manager);
  const missing = DEMO_PRODUCTS.filter((p) => !existing.has(p.title));
  for (const product of missing) {
    await withRetry(`create product ${product.title}`, () =>
      createProduct(
        {
          title: product.title,
          description: product.description,
          categoryId: categoryIds.get(product.category)!,
          basePrice: product.price,
        },
        manager,
      ),
    );
    summary.productsCreated += 1;
  }
  if (missing.length > 0) {
    log(`  created ${missing.length} products`);
    existing = await managerProducts(manager);
  }
  const result = new Map<DemoProduct, ProductSummary>();
  for (const product of DEMO_PRODUCTS) {
    const found = existing.get(product.title);
    if (!found) throw new SeedError(`product "${product.title}" is missing after creation`);
    result.set(product, found);
  }
  return result;
}

async function stockItems(worker: Token): Promise<Map<string, StockItem>> {
  const items = new Map<string, StockItem>();
  for (let page = 0; ; page += 1) {
    const result = await withRetry('list stock', () => listStock({ page, size: 100 }, worker));
    for (const item of result.content) items.set(item.productId, item);
    if ((page + 1) * 100 >= result.totalElements || result.content.length === 0) break;
  }
  return items;
}

async function prepareStock(worker: Token, products: Map<DemoProduct, ProductSummary>) {
  const items = await stockItems(worker);
  for (const [fixture, product] of products) {
    const item = items.get(product.productId);
    // BL-03: workers cannot read packaging values, so configured packaging is left as it is.
    if (!item?.packagingConfigured) {
      await withRetry(`set packaging of ${fixture.title}`, () =>
        setPackaging(product.productId, fixture.packaging, worker),
      );
      summary.packagingSet += 1;
    }
    const delta = fixture.targetStock - (item?.availableQuantity ?? 0);
    if (delta !== 0) {
      await withRetry(`adjust stock of ${fixture.title}`, () =>
        adjustStock(product.productId, delta, worker),
      );
      summary.stockAdjusted += 1;
    }
  }
  log(`  packaging set: ${summary.packagingSet}, stock adjusted: ${summary.stockAdjusted}`);
}

async function setStatuses(manager: Token, products: Map<DemoProduct, ProductSummary>) {
  for (const [fixture, product] of products) {
    const wanted = fixture.active ? 'ACTIVE' : 'NOT_ACTIVE';
    if (product.status === wanted) continue;
    await withRetry(`set ${fixture.title} ${wanted}`, () =>
      setProductStatus(product.productId, wanted, manager),
    );
    if (fixture.active) summary.activated += 1;
    else summary.deactivated += 1;
  }
  log(`  activated: ${summary.activated}, deactivated: ${summary.deactivated}`);
}

/**
 * Gives the demo customer DEMO_CART when their cart is empty (change customer-cart,
 * design D10); a cart they changed is kept. Customer Service does not check
 * products, so the inactive line can be stored.
 */
async function ensureDemoCart(customer: Token, products: Map<DemoProduct, ProductSummary>) {
  const current = await withRetry('read the demo cart', () => getCart(customer));
  if (current.length > 0) {
    log(`  the demo customer's cart has ${current.length} lines; kept`);
    return;
  }
  const idByTitle = new Map(
    [...products].map(([fixture, product]) => [fixture.title, product.productId]),
  );
  const items = DEMO_CART.map((line) => ({
    productId: idByTitle.get(line.title)!,
    quantity: line.quantity,
  }));
  await withRetry('set the demo cart', () => replaceCart({ items }, customer));
  summary.demoCart = 'set';
  log(`  demo cart set: ${items.length} lines`);
}

/** Polls the storefront until stock statuses match (they arrive through Kafka events). */
async function waitForStorefront(products: Map<DemoProduct, ProductSummary>): Promise<string[]> {
  const expected = new Map(
    [...products]
      .filter(([fixture]) => fixture.active)
      .map(([fixture, product]) => [
        product.productId,
        { title: fixture.title, stock: fixture.targetStock > 0 ? 'IN_STOCK' : 'OUT_OF_STOCK' },
      ]),
  );
  const deadline = Date.now() + CONVERGE_SECONDS * 1000;
  let pending: string[] = [];
  for (;;) {
    const seen = new Map<string, ProductSummary>();
    for (let page = 0; ; page += 1) {
      const result = await withRetry('read storefront', () => listProducts({ page, size: 100 }));
      for (const product of result.content) seen.set(product.productId, product);
      if ((page + 1) * 100 >= result.totalElements || result.content.length === 0) break;
    }
    pending = [...expected]
      .filter(([id, want]) => seen.get(id)?.stockStatus !== want.stock)
      .map(
        ([id, want]) =>
          `${want.title} (expected ${want.stock}, is ${seen.get(id)?.stockStatus ?? 'not listed'})`,
      );
    if (pending.length === 0 || Date.now() > deadline) return pending;
    await sleep(2000);
  }
}

async function main() {
  if (!ADMIN_PASSWORD) {
    throw new SeedError(
      'SEED_ADMIN_PASSWORD is required: set it to the stack’s ADMIN_PASSWORD (see the Compose .env).',
    );
  }
  configureHttp({ baseUrl: API_URL });
  log(`Seeding ${API_URL}`);

  log('1. Accounts');
  const admin = await loginAs({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }, 'admin');
  for (const account of [...DEMO_STAFF, DEMO_CUSTOMER]) await ensureAccount(account, admin);
  const manager = await loginAs(
    DEMO_STAFF.find((a) => a.role === 'CATALOG_MANAGER')!,
    'manager',
  );
  const worker = await loginAs(
    DEMO_STAFF.find((a) => a.role === 'INVENTORY_WORKER')!,
    'worker',
  );
  const customer = await loginAs(DEMO_CUSTOMER, 'customer');

  log('2. Categories and products (manager)');
  const categoryIds = await ensureCategories(manager);
  const products = await ensureProducts(manager, categoryIds);
  log(`  ${products.size} fixture products present (${summary.productsCreated} new)`);

  log('3. Packaging and stock (worker)');
  await prepareStock(worker, products);

  log('4. Activation (manager)');
  await setStatuses(manager, products);

  log('5. Demo cart (customer)');
  await ensureDemoCart(customer, products);

  log(`6. Waiting for the storefront (up to ${CONVERGE_SECONDS} s)`);
  const pending = await waitForStorefront(products);

  const changed =
    summary.accountsCreated.length +
    summary.categoriesCreated +
    summary.productsCreated +
    summary.packagingSet +
    summary.stockAdjusted +
    summary.activated +
    summary.deactivated +
    (summary.demoCart === 'set' ? 1 : 0);
  log('');
  log(changed === 0 ? 'Result: unchanged (everything was already seeded).' : 'Result: seeded.');
  log(
    `  accounts created: ${summary.accountsCreated.length}, categories created: ${summary.categoriesCreated}, ` +
      `products created: ${summary.productsCreated}, packaging set: ${summary.packagingSet}, ` +
      `stock adjusted: ${summary.stockAdjusted}, activated: ${summary.activated}, deactivated: ${summary.deactivated}, ` +
      `demo cart: ${summary.demoCart}`,
  );
  log('  Demo accounts:');
  log(`    ${ADMIN_EMAIL} (Admin, password from SEED_ADMIN_PASSWORD)`);
  for (const account of [...DEMO_STAFF, DEMO_CUSTOMER]) {
    log(`    ${account.email} / ${account.password} (${account.label})`);
  }
  if (pending.length > 0) {
    log(`  Not converged after ${CONVERGE_SECONDS} s (${pending.length}):`);
    for (const line of pending) log(`    ${line}`);
    process.exitCode = 2;
  } else {
    log(
      `  Storefront: ${DEMO_PRODUCTS.filter((p) => p.active).length} active products with the expected stock status.`,
    );
  }
}

main().catch((error: unknown) => {
  console.error(`Seed failed: ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
});
