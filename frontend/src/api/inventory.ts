// Inventory Service calls: spec ../docs/design/services-specs/inventory-service-spec.md,
// types generated from ../inventory-service/openapi/public.json (AGENTS.md §3).
import { pathsOf, type JsonBody, type Ok, type Page, type Query, type Received } from './contract';
import type { components, operations, paths } from './generated/inventory';
import { request, type RequestOptions } from './http';

type Auth = RequestOptions['auth'];
type Schemas = components['schemas'];
const pathFor = pathsOf<paths>();

/** EP-INV-01 item. */
export type StockItem = Received<Schemas['StockBalanceResponse']>;

/** EP-INV-01 query: `packagingConfigured`, `page`, `size` (1–100), `sortBy`. */
export type StockQuery = Query<operations['listStock']>;

/** EP-INV-03 body: four positive integers, replaced together. */
export type Packaging = JsonBody<operations['setPackaging']>;

/** EP-INV-01 (`listStock`): stock balances (inventory worker). */
export function listStock(
  query: StockQuery = {},
  auth: Auth = 'session',
): Promise<Page<StockItem>> {
  return request<Page<StockItem>>(pathFor('/inventory/stock'), { query: { ...query }, auth });
}

/**
 * EP-INV-02 (`adjustStock`): restock (`delta > 0`) or write off (`delta < 0`).
 * `422` below zero, `404` unknown product, `503` (Retry-After) when Catalog cannot be asked.
 */
export function adjustStock(
  productId: string,
  delta: number,
  auth: Auth = 'session',
): Promise<Received<Ok<operations['adjustStock']>>> {
  const body: JsonBody<operations['adjustStock']> = { delta };
  return request(pathFor('/inventory/stock/{productId}/adjust', { productId }), {
    method: 'POST',
    body,
    auth,
  });
}

/** EP-INV-03 (`setPackaging`): set packaging; `204 No Content`. Creates the stock item if it is missing. */
export function setPackaging(
  productId: string,
  packaging: Packaging,
  auth: Auth = 'session',
): Promise<Ok<operations['setPackaging']>> {
  return request<Ok<operations['setPackaging']>>(
    pathFor('/inventory/stock/{productId}/packaging', { productId }),
    { method: 'PUT', body: packaging, auth },
  );
}
