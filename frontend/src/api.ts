import { AppData, DashboardMetrics, LedgerEntry, Payment, ReconciliationItem, ReviewCase } from "./types";
import { mockData } from "./mockData";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const REQUEST_TIMEOUT_MS = 2_000;

async function fetchJson<T>(path: string): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${API_BASE_URL}${path}`, { signal: controller.signal });
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}`);
    }
    return (await response.json()) as T;
  } finally {
    clearTimeout(timeout);
  }
}

export async function loadAppData(): Promise<AppData> {
  try {
    const [metrics, payments, reviewCases, reconciliationItems] = await Promise.all([
      fetchJson<DashboardMetrics>("/api/metrics"),
      fetchJson<Payment[]>("/api/payments"),
      fetchJson<ReviewCase[]>("/api/fraud/reviews"),
      fetchJson<ReconciliationItem[]>("/api/reconciliation/reports")
    ]);

    const ledgerEntriesByPayment = await Promise.all(
      payments.map(async (payment) => {
        try {
          return await fetchJson<LedgerEntry[]>(`/api/payments/${payment.id}/ledger`);
        } catch {
          return [] as LedgerEntry[];
        }
      })
    );

    return {
      metrics,
      payments,
      reviewCases,
      reconciliationItems,
      ledgerEntries: ledgerEntriesByPayment.flat()
    };
  } catch {
    return mockData;
  }
}
