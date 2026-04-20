export function asMoney(cents: number, currency = "USD"): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2
  }).format(cents / 100);
}

export function asPct(value: number): string {
  return `${value.toFixed(1)}%`;
}

export function asDate(value: string): string {
  return new Date(value).toLocaleString();
}

export function cx(...values: Array<string | false | undefined>): string {
  return values.filter(Boolean).join(" ");
}
