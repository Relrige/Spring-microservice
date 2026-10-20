// In-memory database of the mock backend, started from the shared demo fixtures (design D10, D14).
import type { Role } from '../api/auth';
import type { Category, ProductStatus, StockStatus } from '../api/catalog';
import {
  DEMO_ACCOUNTS,
  DEMO_CATEGORIES,
  DEMO_PRODUCTS,
  type DemoPackaging,
} from '../fixtures/demo-data';

export interface MockUser {
  id: string;
  /** Normalized: trimmed, lower-cased (Auth spec, Data rules). */
  email: string;
  password: string;
  role: Role;
}

export interface MockProduct {
  productId: string;
  title: string;
  description: string;
  categoryId: string;
  basePrice: number;
  status: ProductStatus;
  stockStatus: StockStatus;
  packaging: DemoPackaging;
  available: number;
}

export interface MockDb {
  users: MockUser[];
  categories: Category[];
  products: MockProduct[];
}

function fixedUuid(group: string, index: number): string {
  return `00000000-0000-4000-${group}-${(index + 1).toString(16).padStart(12, '0')}`;
}

export function createDb(): MockDb {
  const categories = DEMO_CATEGORIES.map((name, index) => ({
    categoryId: fixedUuid('a000', index),
    name,
  }));
  const categoryId = new Map(categories.map((c) => [c.name, c.categoryId]));
  return {
    users: DEMO_ACCOUNTS.map((account, index) => ({
      id: fixedUuid('b000', index),
      email: account.email,
      password: account.password,
      role: account.role,
    })),
    categories,
    products: DEMO_PRODUCTS.map((product, index) => ({
      productId: fixedUuid('8000', index),
      title: product.title,
      description: product.description,
      categoryId: categoryId.get(product.category)!,
      basePrice: product.price,
      status: product.active ? 'ACTIVE' : 'NOT_ACTIVE',
      stockStatus: product.targetStock > 0 ? 'IN_STOCK' : 'OUT_OF_STOCK',
      packaging: product.packaging,
      available: product.targetStock,
    })),
  };
}

/** The live database of the mock; tests reset it with `resetDb()`. */
export let db: MockDb = createDb();

export function resetDb(): void {
  db = createDb();
}
