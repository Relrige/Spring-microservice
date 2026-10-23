// In-memory database of the mock backend, started from the shared demo fixtures (design D10, D14).
import type { Role } from '../api/auth';
import type { Category, ProductStatus, StockStatus } from '../api/catalog';
import type { CartItem, Profile } from '../api/customer';
import type { DeliveryDetails, DeliveryStatus, OrderStatus } from '../api/order';
import {
  DEMO_ACCOUNTS,
  DEMO_CART,
  DEMO_CATEGORIES,
  DEMO_CUSTOMER,
  DEMO_PRODUCTS,
  DEMO_PROFILE,
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
  /** `null` without an image (Catalog task 30). */
  imageUrl: string | null;
  /** `null` until a worker sets it; products created in the mock have none (activation readiness). */
  packaging: DemoPackaging | null;
  available: number;
}

export interface MockDb {
  users: MockUser[];
  categories: Category[];
  products: MockProduct[];
  /** Customer Service carts by user id, ordered by `productId`. */
  carts: Map<string, CartItem[]>;
  /** Customer Service profiles by user id. */
  profiles: Map<string, Profile>;
  /** Order Service orders (change customer-checkout-orders, design D10). */
  orders: MockOrder[];
  /** Submissions by `<userId> <Idempotency-Key>`: request hash and the order placed. */
  idempotencyKeys: Map<string, { hash: string; orderId: string }>;
}

export interface MockOrder {
  orderId: string;
  customerId: string;
  status: OrderStatus;
  items: { productId: string; productTitle: string; unitPriceAtOrder: number; quantity: number }[];
  deliveryDetails: DeliveryDetails;
  totalAmount: number;
  placedAt: string;
  paidAt?: string;
  reservationExpiresAt: string;
  ttn?: string;
  deliveryStatus?: DeliveryStatus;
  failureReason?: string;
  shippedAt?: string;
}

/** The index leads, so short ids (`#00000017`, first 8 characters) differ between rows. */
function fixedUuid(group: string, index: number): string {
  const hex = (index + 1).toString(16);
  return `${hex.padStart(8, '0')}-0000-4000-${group}-${hex.padStart(12, '0')}`;
}

export function createDb(): MockDb {
  const categories = DEMO_CATEGORIES.map((name, index) => ({
    categoryId: fixedUuid('a000', index),
    name,
  }));
  const categoryId = new Map(categories.map((c) => [c.name, c.categoryId]));
  const users = DEMO_ACCOUNTS.map((account, index) => ({
    id: fixedUuid('b000', index),
    email: account.email,
    password: account.password,
    role: account.role,
  }));
  const productId = (title: string) =>
    fixedUuid(
      '8000',
      DEMO_PRODUCTS.findIndex((p) => p.title === title),
    );
  const demoCart = DEMO_CART.map((line) => ({
    productId: productId(line.title),
    quantity: line.quantity,
  })).sort((a, b) => (a.productId < b.productId ? -1 : 1));
  const demoCustomerId = users.find((u) => u.email === DEMO_CUSTOMER.email)!.id;
  return {
    users,
    carts: new Map([[demoCustomerId, demoCart]]),
    profiles: new Map([[demoCustomerId, { ...DEMO_PROFILE }]]),
    orders: [],
    idempotencyKeys: new Map(),
    categories,
    products: DEMO_PRODUCTS.map((product, index) => ({
      productId: fixedUuid('8000', index),
      title: product.title,
      description: product.description,
      categoryId: categoryId.get(product.category)!,
      basePrice: product.price,
      status: product.active ? 'ACTIVE' : 'NOT_ACTIVE',
      stockStatus: product.targetStock > 0 ? 'IN_STOCK' : 'OUT_OF_STOCK',
      imageUrl: null,
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
