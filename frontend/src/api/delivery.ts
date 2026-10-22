// Delivery Service calls: spec ../docs/design/services-specs/delivery-service-spec.md,
// types generated from ../delivery-service/openapi/public.json (AGENTS.md §3).
import { pathsOf, type Ok, type Received } from './contract';
import { problemOf } from './errors';
import type { components, operations, paths } from './generated/delivery';
import { request } from './http';

const pathFor = pathsOf<paths>();

/**
 * EP-DEL-03 (`retryTtnGeneration`): one TTN attempt for a shipment in `TTN_FAILED` (worker).
 * `424` with a `reason` when it fails again, `409` when the shipment is not `TTN_FAILED`.
 */
export function retryTtn(orderId: string): Promise<Received<Ok<operations['retryTtnGeneration']>>> {
  return request(pathFor('/delivery/orders/{orderId}/ttn/retry', { orderId }), { method: 'POST' });
}

/** The `reason` of a failed TTN attempt, e.g. `packaging_data_missing:{productId}`. */
export function ttnFailure(error: unknown): string | undefined {
  return problemOf<components['schemas']['TtnGenerationFailedProblem']>(
    error,
    'ttn-generation-failed',
  )?.reason;
}
