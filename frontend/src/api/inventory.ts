// Inventory Service calls, mirroring ../docs/design/services-specs/inventory-service-spec.md.
import { request, type RequestOptions } from './http';

type Auth = RequestOptions['auth'];

/** EP-INV-01 item: `{ productId, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured }`. */
export interface StockItem {
  productId: string;
  availableQuantity: number;
  reservedQuantity: number;
  totalQuantity: number;
  packagingConfigured: boolean;
}

/** EP-INV-01 paging envelope: `{ content, page, size, totalElements }`. */
export interface StockPage {
  content: StockItem[];
  page: number;
  size: number;
  totalElements: number;
}

/** EP-INV-01 query: `packagingConfigured`, `page`, `size` (1–100), `sortBy`. */
export interface StockQuery {
  packagingConfigured?: boolean;
  page?: number;
  size?: number;
  sortBy?: string;
}

/** EP-INV-03 body: four positive integers, replaced together. */
export interface Packaging {
  grossWeightGrams: number;
  lengthCm: number;
  widthCm: number;
  heightCm: number;
}

/** EP-INV-01: stock balances (inventory worker). */
export function listStock(query: StockQuery = {}, auth: Auth = 'session'): Promise<StockPage> {
  return request<StockPage>('/inventory/stock', { query: { ...query }, auth });
}

/**
 * EP-INV-02: restock (`delta > 0`) or write off (`delta < 0`). `422` below zero,
 * `404` unknown product, `503` (Retry-After) when Catalog cannot be asked.
 */
export function adjustStock(
  productId: string,
  delta: number,
  auth: Auth = 'session',
): Promise<{ newAvailableQuantity: number }> {
  return request(`/inventory/stock/${encodeURIComponent(productId)}/adjust`, {
    method: 'POST',
    body: { delta },
    auth,
  });
}

/** EP-INV-03: set packaging; `204 No Content`. Creates the stock item if it is missing. */
export function setPackaging(
  productId: string,
  packaging: Packaging,
  auth: Auth = 'session',
): Promise<void> {
  return request<void>(`/inventory/stock/${encodeURIComponent(productId)}/packaging`, {
    method: 'PUT',
    body: packaging,
    auth,
  });
}
