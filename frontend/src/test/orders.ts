import { db, type MockOrder } from '../mocks/db';

export const ORDER_PRODUCT = 'Sony WH-1000XM5 headphones';

/** Adds an order of the demo customer (1 × Sony headphones, awaiting payment) to the mock. */
export function addOrder(overrides: Partial<MockOrder> = {}): MockOrder {
  const product = db.products.find((p) => p.title === ORDER_PRODUCT)!;
  const now = Date.now();
  const order: MockOrder = {
    orderId: crypto.randomUUID(),
    customerId: db.users.find((u) => u.role === 'CUSTOMER')!.id,
    status: 'PENDING_PAYMENT',
    items: [
      {
        productId: product.productId,
        productTitle: product.title,
        unitPriceAtOrder: product.basePrice,
        quantity: 1,
      },
    ],
    deliveryDetails: {
      name: 'Olena Kovalenko',
      phone: '+380501234567',
      address: 'Kyiv, branch No. 12',
      carrier: 'NOVA_POSHTA',
    },
    totalAmount: product.basePrice,
    placedAt: new Date(now).toISOString(),
    reservationExpiresAt: new Date(now + 15 * 60_000).toISOString(),
    ...overrides,
  };
  db.orders.push(order);
  return order;
}
