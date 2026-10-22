/**
 * Line total in whole kopecks (design D5): the one place the client does arithmetic
 * on prices, so the estimated subtotal does not drift with floating-point sums.
 */
export function lineKopecks(price: number, quantity: number): number {
  return Math.round(price * 100) * quantity;
}

/** The estimated subtotal in UAH of available lines. */
export function estimatedSubtotal(lines: { price: number; quantity: number }[]): number {
  return lines.reduce((sum, line) => sum + lineKopecks(line.price, line.quantity), 0) / 100;
}
