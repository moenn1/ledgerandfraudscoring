import {
  AppData,
  AppLoadResult,
  DashboardMetrics,
  LedgerEntry,
  Payment,
  PaymentEvent,
  PaymentStatus,
  ReconciliationItem,
  ReviewCase
} from "./types";
import { apiBaseUrl, apiBearerToken } from "./config";
import { deriveAuditEntries, deriveRepairRecommendations, deriveRetryAttempts } from "./investigation";
import { mockData } from "./mockData";

const REQUEST_TIMEOUT_MS = 2_000;

interface ApiPayment {
  id: string;
  payerAccountId: string;
  payeeAccountId: string;
  amount: number | string;
  currency: string;
  status: PaymentStatus;
  idempotencyKey: string;
  riskScore: number | null;
  riskDecision: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

interface ApiLedgerEntry {
  id: string;
  journalId: string;
  accountId: string;
  direction: "DEBIT" | "CREDIT";
  amount: number | string;
  currency: string;
  createdAt: string;
}

interface ApiReviewCase {
  id: string;
  paymentId: string;
  reason: string;
  status: ReviewCase["status"];
  assignedTo: string;
  createdAt: string;
  updatedAt: string;
}

class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function fetchJson<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const headers = new Headers(init?.headers);
    if (apiBearerToken && !headers.has("Authorization")) {
      headers.set("Authorization", `Bearer ${apiBearerToken}`);
    }
    if (init?.body && !headers.has("Content-Type")) {
      headers.set("Content-Type", "application/json");
    }

    const response = await fetch(`${apiBaseUrl}${path}`, {
      ...init,
      headers,
      signal: controller.signal
    });
    if (!response.ok) {
      throw new ApiError(response.status, await extractErrorMessage(response));
    }
    return (await response.json()) as T;
  } finally {
    clearTimeout(timeout);
  }
}

async function tryFetchJson<T>(path: string): Promise<T | null> {
  try {
    return await fetchJson<T>(path);
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      throw error;
    }
    return null;
  }
}

export async function loadAppData(): Promise<AppLoadResult> {
  const paymentsResponse = await tryFetchJson<ApiPayment[]>("/api/payments");

  if (paymentsResponse === null) {
    return {
      data: mockData,
      meta: {
        sourceMode: "MOCK",
        sourceLabel: "Mock fallback",
        reviewActionMode: "LOCAL",
        warnings: [
          "Core payment endpoints are unavailable, so the console is running from in-repo sample data.",
          "Live operator actions are unavailable until the backend API can be reached, but local mock review actions remain enabled for demo flows."
        ]
      }
    };
  }

  const warnings: string[] = [];
  const payments = paymentsResponse.map(normalizePayment);
  const [reviewCasesResponse, metricsResponse, reconciliationResponse] = await Promise.all([
    tryFetchJson<ApiReviewCase[]>("/api/fraud/reviews"),
    tryFetchJson<DashboardMetrics>("/api/metrics"),
    tryFetchJson<ReconciliationItem[]>("/api/reconciliation/reports")
  ]);

  const ledgerEntriesByPayment = await Promise.all(
    payments.map(async (payment) => {
      const response = await tryFetchJson<ApiLedgerEntry[]>(`/api/payments/${payment.id}/ledger`);
      if (response === null) {
        warnings.push(`Ledger detail is unavailable for payment ${payment.id}; journal legs are omitted for that payment.`);
        return [] as LedgerEntry[];
      }
      return response.map((entry) => normalizeLedgerEntry(entry, payment.id));
    })
  );

  const ledgerEntries = ledgerEntriesByPayment.flat();
  const reviewCases = reviewCasesResponse ? reviewCasesResponse.map(normalizeReviewCase) : [];
  if (reviewCasesResponse === null) {
    warnings.push("Manual review queue endpoint is unavailable; fraud console is read-only until that API comes online.");
  }

  const reconciliationItems =
    reconciliationResponse ?? deriveReconciliationItems(payments, reviewCases, ledgerEntries);
  if (reconciliationResponse === null) {
    warnings.push("Reconciliation endpoint is unavailable; anomaly rows are derived from live ledger state.");
  }

  const metrics =
    metricsResponse ?? deriveMetrics(payments, reviewCases, ledgerEntries, reconciliationItems.length);
  if (metricsResponse === null) {
    warnings.push("Metrics endpoint is unavailable; mission-control cards are derived from live payment and ledger data.");
  }

  return {
    data: {
      metrics,
      payments,
      reviewCases,
      reconciliationItems,
      ledgerEntries,
      auditEntries: deriveAuditEntries(payments, ledgerEntries, reviewCases, reconciliationItems),
      retryAttempts: deriveRetryAttempts(payments),
      repairRecommendations: deriveRepairRecommendations(reconciliationItems, payments, reviewCases)
    },
    meta: {
      sourceMode: warnings.length === 0 ? "LIVE" : "HYBRID",
      sourceLabel: warnings.length === 0 ? "Live API" : "API + derived fallback",
      reviewActionMode: reviewCasesResponse ? "API" : "READ_ONLY",
      warnings
    }
  };
}

export async function submitReviewDecision(
  reviewCaseId: string,
  decision: "APPROVE" | "REJECT",
  note: string,
  correlationId: string
): Promise<ReviewCase> {
  const response = await fetchJson<ApiReviewCase>(`/api/fraud/reviews/${reviewCaseId}/decision`, {
    method: "POST",
    headers: {
      "X-Correlation-Id": correlationId
    },
    body: JSON.stringify({
      decision,
      note
    })
  });

  return normalizeReviewCase(response);
}

async function extractErrorMessage(response: Response): Promise<string> {
  const fallback = `${response.status} ${response.statusText}`;
  const contentType = response.headers.get("Content-Type") ?? "";
  if (!contentType.includes("application/json")) {
    const text = await response.text();
    return text || fallback;
  }
  try {
    const payload = (await response.json()) as { message?: string };
    return payload.message || fallback;
  } catch {
    return fallback;
  }
}

function normalizePayment(payment: ApiPayment): Payment {
  return {
    id: payment.id,
    payerAccountId: payment.payerAccountId,
    payeeAccountId: payment.payeeAccountId,
    amount: decimalToCents(payment.amount),
    currency: payment.currency,
    status: payment.status,
    idempotencyKey: payment.idempotencyKey,
    riskScore: payment.riskScore ?? 0,
    decision: normalizeDecision(payment.riskDecision, payment.status),
    createdAt: payment.createdAt,
    updatedAt: payment.updatedAt,
    reasonCodes: buildReasonCodes(payment),
    events: buildPaymentEvents(payment)
  };
}

function normalizeLedgerEntry(entry: ApiLedgerEntry, paymentId: string): LedgerEntry {
  return {
    id: entry.id,
    journalId: entry.journalId,
    accountId: entry.accountId,
    direction: entry.direction,
    amount: decimalToCents(entry.amount),
    currency: entry.currency,
    paymentId,
    createdAt: entry.createdAt
  };
}

function normalizeReviewCase(reviewCase: ApiReviewCase): ReviewCase {
  return {
    id: reviewCase.id,
    paymentId: reviewCase.paymentId,
    reason: reviewCase.reason,
    status: reviewCase.status,
    assignedTo: reviewCase.assignedTo,
    createdAt: reviewCase.createdAt
  };
}

function normalizeDecision(riskDecision: string | null, status: PaymentStatus): Payment["decision"] {
  if (riskDecision === "APPROVE" || riskDecision === "REJECT" || riskDecision === "REVIEW") {
    return riskDecision;
  }
  if (status === "REJECTED") {
    return "REJECT";
  }
  return "APPROVE";
}

function buildReasonCodes(payment: ApiPayment): string[] {
  if (payment.failureReason) {
    return [payment.failureReason];
  }
  if (payment.riskDecision === "REJECT") {
    return ["risk_policy_rejected"];
  }
  if (payment.riskDecision === "REVIEW") {
    return ["manual_review_required"];
  }
  return [];
}

function buildPaymentEvents(payment: ApiPayment): PaymentEvent[] {
  const statuses = timelineForStatus(payment.status);
  return statuses.map((status, index) => ({
    id: `${payment.id}-${status.toLowerCase()}-${index}`,
    type: status.toLowerCase(),
    status,
    timestamp: interpolateTimestamp(payment.createdAt, payment.updatedAt, index, statuses.length),
    actor: actorForStatus(status),
    note: noteForStatus(status, payment)
  }));
}

function timelineForStatus(status: PaymentStatus): PaymentStatus[] {
  switch (status) {
    case "CREATED":
      return ["CREATED"];
    case "VALIDATED":
      return ["CREATED", "VALIDATED"];
    case "RISK_SCORING":
      return ["CREATED", "VALIDATED", "RISK_SCORING"];
    case "APPROVED":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "APPROVED"];
    case "RESERVED":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "APPROVED", "RESERVED"];
    case "CAPTURED":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "APPROVED", "RESERVED", "CAPTURED"];
    case "SETTLED":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "APPROVED", "RESERVED", "CAPTURED", "SETTLED"];
    case "REJECTED":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "REJECTED"];
    case "REVERSED":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "APPROVED", "RESERVED", "REVERSED"];
    case "CHARGEBACK":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "APPROVED", "RESERVED", "CAPTURED", "CHARGEBACK"];
    case "REFUNDED":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "APPROVED", "RESERVED", "CAPTURED", "REFUNDED"];
    case "CANCELLED":
      return ["CREATED", "VALIDATED", "RISK_SCORING", "APPROVED", "RESERVED", "CANCELLED"];
    default:
      return ["CREATED"];
  }
}

function noteForStatus(status: PaymentStatus, payment: ApiPayment): string {
  switch (status) {
    case "CREATED":
      return "Payment intent accepted by the API.";
    case "VALIDATED":
      return "Currency, accounts, and amount validation completed.";
    case "RISK_SCORING":
      return `Fraud evaluation computed score ${payment.riskScore ?? 0}.`;
    case "APPROVED":
      return "Risk policy approved the payment for execution.";
    case "RESERVED":
      return "Reserve journal posted against payer and holding accounts.";
    case "CAPTURED":
      return "Capture journal posted to payee and platform revenue accounts.";
    case "SETTLED":
      return "Settlement completed and balances were projected from the ledger.";
    case "REJECTED":
      return payment.failureReason ?? "Payment rejected by fraud policy.";
    case "REVERSED":
      return "Reserve reversal journal posted and funds released.";
    case "CHARGEBACK":
      return "Dispute chargeback journal posted back to the payer.";
    case "REFUNDED":
      return "Refund journal posted back to the payer.";
    case "CANCELLED":
      return "Payment was cancelled before final execution.";
    default:
      return "Payment state changed.";
  }
}

function actorForStatus(status: PaymentStatus): string {
  if (status === "CREATED") return "api-client";
  if (status === "REJECTED") return "fraud-engine";
  if (status === "CANCELLED") return "operator";
  return "payments-orchestrator";
}

function interpolateTimestamp(startIso: string, endIso: string, index: number, total: number): string {
  const start = Date.parse(startIso);
  const end = Math.max(start, Date.parse(endIso));
  if (!Number.isFinite(start) || !Number.isFinite(end) || total <= 1) {
    return endIso;
  }
  const ratio = index / (total - 1);
  return new Date(start + (end - start) * ratio).toISOString();
}

function deriveMetrics(
  payments: Payment[],
  reviewCases: ReviewCase[],
  ledgerEntries: LedgerEntry[],
  anomalyCount: number
): DashboardMetrics {
  const totalPayments = payments.length;
  const approvedCount = payments.filter((payment) => payment.decision === "APPROVE").length;
  const rejectedCount = payments.filter((payment) => payment.decision === "REJECT").length;
  const openReviewCount = reviewCases.filter((reviewCase) => reviewCase.status === "OPEN").length;
  const latencies = payments
    .map((payment) => Math.max(0, Date.parse(payment.updatedAt) - Date.parse(payment.createdAt)))
    .sort((left, right) => left - right);

  return {
    processedToday: payments.filter((payment) => isSameUtcDay(payment.createdAt, new Date())).length,
    approvedRate: totalPayments === 0 ? 0 : (approvedCount / totalPayments) * 100,
    rejectedRate: totalPayments === 0 ? 0 : (rejectedCount / totalPayments) * 100,
    reviewQueueCount: openReviewCount,
    ledgerHealth: hasLedgerDrift(ledgerEntries) ? "DEGRADED" : "HEALTHY",
    p95LatencyMs: percentile(latencies, 0.95),
    anomalyCount
  };
}

function deriveReconciliationItems(
  payments: Payment[],
  reviewCases: ReviewCase[],
  ledgerEntries: LedgerEntry[]
): ReconciliationItem[] {
  const entriesByPayment = new Map<string, LedgerEntry[]>();
  for (const entry of ledgerEntries) {
    const current = entriesByPayment.get(entry.paymentId) ?? [];
    current.push(entry);
    entriesByPayment.set(entry.paymentId, current);
  }

  const reviewCaseIds = new Set(reviewCases.map((reviewCase) => reviewCase.paymentId));
  const anomalies: ReconciliationItem[] = [];

  for (const payment of payments) {
    const entries = entriesByPayment.get(payment.id) ?? [];
    const net = entries.reduce(
      (total, entry) => total + (entry.direction === "CREDIT" ? entry.amount : -entry.amount),
      0
    );

    if (entries.length > 0 && net !== 0) {
      anomalies.push({
        id: `recon-balance-${payment.id}`,
        category: "MISMATCH",
        severity: "HIGH",
        paymentId: payment.id,
        createdAt: payment.updatedAt,
        details: `Ledger net is ${net} cents for payment ${payment.id}; operator investigation is required.`
      });
    }

    if (payment.decision === "REVIEW" && !reviewCaseIds.has(payment.id)) {
      anomalies.push({
        id: `recon-review-${payment.id}`,
        category: "MISSING_EVENT",
        severity: "MEDIUM",
        paymentId: payment.id,
        createdAt: payment.updatedAt,
        details: "Payment requires manual review but no review case is present in the operator queue."
      });
    }

    if (["CAPTURED", "SETTLED", "REFUNDED", "REVERSED"].includes(payment.status) && entries.length === 0) {
      anomalies.push({
        id: `recon-ledger-${payment.id}`,
        category: "MISSING_EVENT",
        severity: "HIGH",
        paymentId: payment.id,
        createdAt: payment.updatedAt,
        details: "Executed payment has no corresponding ledger legs from the live API."
      });
    }
  }

  return anomalies;
}

function hasLedgerDrift(ledgerEntries: LedgerEntry[]): boolean {
  const totalsByJournal = new Map<string, number>();

  for (const entry of ledgerEntries) {
    const signedAmount = entry.direction === "CREDIT" ? entry.amount : -entry.amount;
    totalsByJournal.set(entry.journalId, (totalsByJournal.get(entry.journalId) ?? 0) + signedAmount);
  }

  return [...totalsByJournal.values()].some((total) => total !== 0);
}

function isSameUtcDay(isoString: string, reference: Date): boolean {
  const value = new Date(isoString);
  return (
    value.getUTCFullYear() === reference.getUTCFullYear() &&
    value.getUTCMonth() === reference.getUTCMonth() &&
    value.getUTCDate() === reference.getUTCDate()
  );
}

function percentile(values: number[], quantile: number): number {
  if (values.length === 0) {
    return 0;
  }
  const index = Math.min(values.length - 1, Math.max(0, Math.ceil(values.length * quantile) - 1));
  return Math.round(values[index] ?? 0);
}

function decimalToCents(value: number | string): number {
  return Math.round(Number(value) * 100);
}
