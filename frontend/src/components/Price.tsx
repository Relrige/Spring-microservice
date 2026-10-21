const formatter = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'UAH',
  currencyDisplay: 'code',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** `UAH 1,299.00` (design D9, design-system README: code, space, thousands comma, two decimals). */
export function formatPrice(value: number): string {
  return formatter.format(value).replace(/\s/g, ' ');
}

export function Price({ value, large = false }: { value: number; large?: boolean }) {
  return <span className={large ? 'vs-price vs-price-lg' : 'vs-price'}>{formatPrice(value)}</span>;
}
