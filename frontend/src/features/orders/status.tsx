// Order and delivery status words and chip tones (design-system README, Status words).
import type { DeliveryStatus, OrderStatus } from '../../api/order';
import { StatusChip, type Tone } from '../../components/StatusChip';

type Word = { label: string; tone: Tone };

export const ORDER_STATUS: Record<OrderStatus, Word> = {
  PENDING_PAYMENT: { label: 'Awaiting payment', tone: 'warning' },
  PAID: { label: 'Paid', tone: 'primary' },
  PENDING_SHIPPING: { label: 'Ready to ship', tone: 'primary' },
  SHIPPED: { label: 'Shipped', tone: 'primary' },
  DELIVERED: { label: 'Delivered', tone: 'success' },
  CANCELLATION_REQUESTED: { label: 'Cancelling', tone: 'warning' },
  CANCELLED: { label: 'Cancelled', tone: 'danger' },
  EXPIRED: { label: 'Expired', tone: 'neutral' },
};

// IN_TRANSIT, DELIVERED and CANCELLED have no README entry (change customer-checkout-orders, design D8).
export const DELIVERY_STATUS: Record<DeliveryStatus, Word> = {
  TTN_PENDING: { label: 'TTN pending', tone: 'warning' },
  TTN_GENERATED: { label: 'TTN ready', tone: 'success' },
  TTN_FAILED: { label: 'TTN failed', tone: 'danger' },
  IN_TRANSIT: { label: 'In transit', tone: 'primary' },
  DELIVERED: { label: 'Delivered', tone: 'success' },
  CANCELLED: { label: 'Cancelled', tone: 'danger' },
};

/** Statuses that change on their own, so a shown order is read again (BL-13). */
export const CHANGING: ReadonlySet<OrderStatus> = new Set([
  'PENDING_PAYMENT',
  'PAID',
  'PENDING_SHIPPING',
  'SHIPPED',
  'CANCELLATION_REQUESTED',
]);

/** Statuses a customer may cancel (EP-ORD-06). */
export const CANCELLABLE: ReadonlySet<OrderStatus> = new Set([
  'PENDING_PAYMENT',
  'PAID',
  'PENDING_SHIPPING',
]);

export function OrderStatusChip({ status }: { status: OrderStatus }) {
  const { label, tone } = ORDER_STATUS[status];
  return <StatusChip tone={tone}>{label}</StatusChip>;
}

export function DeliveryStatusChip({ status }: { status: DeliveryStatus }) {
  const { label, tone } = DELIVERY_STATUS[status];
  return <StatusChip tone={tone}>{label}</StatusChip>;
}
