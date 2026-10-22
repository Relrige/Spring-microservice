// Words for Catalog's and Inventory's refusals (specs product-management and
// category-management; design D7, D9).
import { activationRejection, type ActivationRejected } from '../../api/catalog';
import { ApiError, userMessage } from '../../api/errors';

/** Inventory's documented readiness codes (EP-INV readiness, via EP-CAT-03 `reason`). */
export const ACTIVATION_REASONS: Record<string, string> = {
  packaging_data_missing:
    'This product cannot be activated yet: its packaging size and weight are not set. An inventory worker sets them in the warehouse.',
  stock_item_not_found:
    'This product cannot be activated yet: the warehouse has no stock record for it. Try again in a moment.',
};

export function activationMessage(problem: ActivationRejected): string {
  return (
    ACTIVATION_REASONS[problem.reason] ??
    `This product cannot be activated: ${problem.detail ?? problem.reason}`
  );
}

export const OVERLAP_MESSAGE =
  'This product was changed by someone else at the same time. Reload it and try again.';
export const CATEGORY_GONE_FOR_PRODUCT = 'This category no longer exists. Choose another one.';
export const CATEGORY_NAME_IN_USE = 'A category with this name already exists.';
export const CATEGORY_GONE = 'This category no longer exists.';
export const CATEGORY_HAS_PRODUCTS =
  'This category still has products. Move them to another category first.';

/** `409` on a product write: the overlapping save of *Product version*. */
export function isConflict(error: unknown): boolean {
  return error instanceof ApiError && error.kind === 'conflict';
}

/** The message of a failed status change; `conflict` asks for a reload (design D7). */
export function statusErrorMessage(error: unknown): { message: string; conflict: boolean } {
  const rejection = activationRejection(error);
  if (rejection) return { message: activationMessage(rejection), conflict: false };
  if (isConflict(error)) return { message: OVERLAP_MESSAGE, conflict: true };
  return { message: userMessage(error), conflict: false };
}
