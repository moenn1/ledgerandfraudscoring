import { LedgerEntry, Payment, ReconciliationItem, RetryAttempt, ReviewCase } from "./types";

const LEDGER_REQUIRED_STATUSES = new Set(["RESERVED", "CAPTURED", "SETTLED", "REFUNDED", "REVERSED", "CHARGEBACK"]);

interface RiskBandConfig {
  label: string;
  min: number;
  max: number;
}

const RISK_BANDS: RiskBandConfig[] = [
  { label: "Low", min: 0, max: 29 },
  { label: "Guarded", min: 30, max: 59 },
  { label: "Escalate", min: 60, max: 79 },
  { label: "Block", min: 80, max: 100 }
];

export interface AnalyticsInput {
  payments: Payment[];
  ledgerEntries: LedgerEntry[];
  reviewCases: ReviewCase[];
  reconciliationItems: ReconciliationItem[];
  retryAttempts: RetryAttempt[];
}

export interface AnalyticsData {
  headline: {
    approvalRate: number;
    rejectRate: number;
    reviewRate: number;
    settlementCoverageRate: number;
    anomalyRate: number;
    driftedJournalCount: number;
    executedWithoutLedgerCount: number;
    oldestBacklogMinutes: number;
  };
  trends: TrendBucket[];
  riskBands: RiskBandSummary[];
  settlement: SettlementHealth;
  latency: LatencyReport;
  backlog: BacklogItem[];
  ownerWorkloads: OwnerWorkload[];
  anomaliesByCategory: RollupItem[];
  anomaliesBySeverity: RollupItem[];
  statusRows: StatusRow[];
}

export interface TrendBucket {
  label: string;
  total: number;
  approved: number;
  review: number;
  rejected: number;
  settled: number;
  anomalies: number;
}

export interface RiskBandSummary {
  label: string;
  range: string;
  count: number;
  share: number;
  openReviewCount: number;
  anomalyCount: number;
}

export interface SettlementHealth {
  ledgerRequiredCount: number;
  balancedCount: number;
  driftedPaymentCount: number;
  missingLedgerCount: number;
  settledCount: number;
  capturedCount: number;
  reservedCount: number;
  refundedCount: number;
  chargebackCount: number;
}

export interface LatencyReport {
  averageMs: number;
  p50Ms: number;
  p95Ms: number;
  maxMs: number;
  longestPaymentId: string | null;
  longestStatus: Payment["status"] | null;
}

export interface BacklogItem {
  reviewCaseId: string;
  paymentId: string;
  owner: string;
  reason: string;
  paymentStatus: Payment["status"] | "UNKNOWN";
  riskScore: number;
  ageMinutes: number;
  anomalyCount: number;
  retryCount: number;
}

export interface OwnerWorkload {
  owner: string;
  openCases: number;
  averageAgeMinutes: number;
  highSeveritySignals: number;
}

export interface RollupItem {
  label: string;
  count: number;
}

export interface StatusRow {
  status: Payment["status"];
  count: number;
  averageRiskScore: number;
  averageLatencyMs: number;
  openReviewCount: number;
  ledgerCoverageRate: number | null;
}

export function deriveAnalytics(input: AnalyticsInput): AnalyticsData {
  const { payments, ledgerEntries, reviewCases, reconciliationItems, retryAttempts } = input;
  const now = Date.now();
  const totalPayments = payments.length;
  const paymentsById = new Map(payments.map((payment) => [payment.id, payment]));
  const openReviewPaymentIds = new Set(
    reviewCases.filter((reviewCase) => reviewCase.status === "OPEN").map((reviewCase) => reviewCase.paymentId)
  );
  const anomaliesByPayment = groupCounts(reconciliationItems, (item) => item.paymentId);
  const highSeverityByPayment = groupCounts(
    reconciliationItems.filter((item) => item.severity === "HIGH"),
    (item) => item.paymentId
  );
  const retryCountsByPayment = groupCounts(retryAttempts, (attempt) => attempt.paymentId);
  const entriesByPayment = groupItems(ledgerEntries, (entry) => entry.paymentId);
  const journalTotals = new Map<string, number>();

  for (const entry of ledgerEntries) {
    const signedAmount = entry.direction === "CREDIT" ? entry.amount : -entry.amount;
    journalTotals.set(entry.journalId, (journalTotals.get(entry.journalId) ?? 0) + signedAmount);
  }

  const driftedJournalCount = [...journalTotals.values()].filter((total) => total !== 0).length;
  const ledgerRequiredPayments = payments.filter((payment) => LEDGER_REQUIRED_STATUSES.has(payment.status));
  let balancedCount = 0;
  let driftedPaymentCount = 0;
  let missingLedgerCount = 0;

  for (const payment of ledgerRequiredPayments) {
    const entries = entriesByPayment.get(payment.id) ?? [];
    if (entries.length === 0) {
      missingLedgerCount += 1;
      continue;
    }

    if (paymentNet(entries) === 0) {
      balancedCount += 1;
    } else {
      driftedPaymentCount += 1;
    }
  }

  const openBacklog = reviewCases
    .filter((reviewCase) => reviewCase.status === "OPEN")
    .map<BacklogItem>((reviewCase) => {
      const payment = paymentsById.get(reviewCase.paymentId);

      return {
        reviewCaseId: reviewCase.id,
        paymentId: reviewCase.paymentId,
        owner: reviewCase.assignedTo,
        reason: reviewCase.reason,
        paymentStatus: payment?.status ?? "UNKNOWN",
        riskScore: payment?.riskScore ?? 0,
        ageMinutes: ageMinutes(reviewCase.createdAt, now),
        anomalyCount: anomaliesByPayment.get(reviewCase.paymentId) ?? 0,
        retryCount: retryCountsByPayment.get(reviewCase.paymentId) ?? 0
      };
    })
    .sort((left, right) => right.ageMinutes - left.ageMinutes || right.riskScore - left.riskScore);

  const ownerWorkloads = [...groupItems(openBacklog, (item) => item.owner).entries()]
    .map<OwnerWorkload>(([owner, items]) => ({
      owner,
      openCases: items.length,
      averageAgeMinutes: average(items.map((item) => item.ageMinutes)),
      highSeveritySignals: items.reduce((total, item) => total + (highSeverityByPayment.get(item.paymentId) ?? 0), 0)
    }))
    .sort((left, right) => right.openCases - left.openCases || right.averageAgeMinutes - left.averageAgeMinutes);

  const latencies = payments.map((payment) => ({
    paymentId: payment.id,
    status: payment.status,
    value: paymentLatencyMs(payment)
  }));
  const longestLatency = [...latencies].sort((left, right) => right.value - left.value)[0];

  return {
    headline: {
      approvalRate: rate(payments.filter((payment) => payment.decision === "APPROVE").length, totalPayments),
      rejectRate: rate(payments.filter((payment) => payment.decision === "REJECT").length, totalPayments),
      reviewRate: rate(payments.filter((payment) => payment.decision === "REVIEW").length, totalPayments),
      settlementCoverageRate: rate(balancedCount, ledgerRequiredPayments.length),
      anomalyRate: rate(reconciliationItems.length, totalPayments),
      driftedJournalCount,
      executedWithoutLedgerCount: missingLedgerCount,
      oldestBacklogMinutes: openBacklog[0]?.ageMinutes ?? 0
    },
    trends: buildTrendBuckets(payments, anomaliesByPayment),
    riskBands: buildRiskBands(payments, openReviewPaymentIds, anomaliesByPayment),
    settlement: {
      ledgerRequiredCount: ledgerRequiredPayments.length,
      balancedCount,
      driftedPaymentCount,
      missingLedgerCount,
      settledCount: payments.filter((payment) => payment.status === "SETTLED").length,
      capturedCount: payments.filter((payment) => payment.status === "CAPTURED").length,
      reservedCount: payments.filter((payment) => payment.status === "RESERVED").length,
      refundedCount: payments.filter((payment) => payment.status === "REFUNDED").length,
      chargebackCount: payments.filter((payment) => payment.status === "CHARGEBACK").length
    },
    latency: {
      averageMs: average(latencies.map((item) => item.value)),
      p50Ms: percentile(latencies.map((item) => item.value), 0.5),
      p95Ms: percentile(latencies.map((item) => item.value), 0.95),
      maxMs: Math.max(0, ...latencies.map((item) => item.value)),
      longestPaymentId: longestLatency?.paymentId ?? null,
      longestStatus: longestLatency?.status ?? null
    },
    backlog: openBacklog,
    ownerWorkloads,
    anomaliesByCategory: buildRollup(reconciliationItems, (item) => item.category),
    anomaliesBySeverity: buildRollup(reconciliationItems, (item) => item.severity),
    statusRows: buildStatusRows(payments, entriesByPayment, openReviewPaymentIds)
  };
}

function buildTrendBuckets(payments: Payment[], anomaliesByPayment: Map<string, number>): TrendBucket[] {
  if (payments.length === 0) {
    return [];
  }

  const times = payments.map((payment) => Date.parse(payment.createdAt)).filter(Number.isFinite);
  const oldest = Math.min(...times);
  const newest = Math.max(...times);
  const bucketCount = Math.max(3, Math.min(6, payments.length));
  const bucketSize = Math.max(15 * 60_000, Math.ceil((Math.max(newest - oldest, 1) + 1) / bucketCount));
  const buckets: TrendBucket[] = Array.from({ length: bucketCount }, (_, index) => {
    const start = oldest + index * bucketSize;
    const end = index === bucketCount - 1 ? newest + 1 : start + bucketSize;
    return {
      label: `${formatClock(start)}-${formatClock(end)}`,
      total: 0,
      approved: 0,
      review: 0,
      rejected: 0,
      settled: 0,
      anomalies: 0
    };
  });

  for (const payment of payments) {
    const createdAt = Date.parse(payment.createdAt);
    const index = Math.min(bucketCount - 1, Math.max(0, Math.floor((createdAt - oldest) / bucketSize)));
    const bucket = buckets[index];
    bucket.total += 1;
    if (payment.decision === "APPROVE") bucket.approved += 1;
    if (payment.decision === "REVIEW") bucket.review += 1;
    if (payment.decision === "REJECT") bucket.rejected += 1;
    if (payment.status === "SETTLED") bucket.settled += 1;
    bucket.anomalies += anomaliesByPayment.get(payment.id) ?? 0;
  }

  return buckets;
}

function buildRiskBands(
  payments: Payment[],
  openReviewPaymentIds: Set<string>,
  anomaliesByPayment: Map<string, number>
): RiskBandSummary[] {
  return RISK_BANDS.map((band) => {
    const items = payments.filter((payment) => payment.riskScore >= band.min && payment.riskScore <= band.max);
    const openReviewCount = items.filter((payment) => openReviewPaymentIds.has(payment.id)).length;
    const anomalyCount = items.reduce((total, payment) => total + (anomaliesByPayment.get(payment.id) ?? 0), 0);

    return {
      label: band.label,
      range: `${band.min}-${band.max}`,
      count: items.length,
      share: rate(items.length, payments.length),
      openReviewCount,
      anomalyCount
    };
  });
}

function buildStatusRows(
  payments: Payment[],
  entriesByPayment: Map<string, LedgerEntry[]>,
  openReviewPaymentIds: Set<string>
): StatusRow[] {
  const groups = groupItems(payments, (payment) => payment.status);

  return [...groups.entries()]
    .map<StatusRow>(([status, items]) => {
      const ledgerEligible = items.filter((payment) => LEDGER_REQUIRED_STATUSES.has(payment.status));
      const coveredCount = ledgerEligible.filter((payment) => (entriesByPayment.get(payment.id) ?? []).length > 0).length;

      return {
        status,
        count: items.length,
        averageRiskScore: average(items.map((payment) => payment.riskScore)),
        averageLatencyMs: average(items.map((payment) => paymentLatencyMs(payment))),
        openReviewCount: items.filter((payment) => openReviewPaymentIds.has(payment.id)).length,
        ledgerCoverageRate: ledgerEligible.length === 0 ? null : rate(coveredCount, ledgerEligible.length)
      };
    })
    .sort((left, right) => right.count - left.count || left.status.localeCompare(right.status));
}

function buildRollup<T>(items: T[], keySelector: (item: T) => string): RollupItem[] {
  return [...groupCounts(items, keySelector).entries()]
    .map(([label, count]) => ({ label, count }))
    .sort((left, right) => right.count - left.count || left.label.localeCompare(right.label));
}

function groupCounts<T>(items: T[], keySelector: (item: T) => string): Map<string, number> {
  const counts = new Map<string, number>();

  for (const item of items) {
    const key = keySelector(item);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }

  return counts;
}

function groupItems<T>(items: T[], keySelector: (item: T) => string): Map<string, T[]> {
  const groups = new Map<string, T[]>();

  for (const item of items) {
    const key = keySelector(item);
    const current = groups.get(key) ?? [];
    current.push(item);
    groups.set(key, current);
  }

  return groups;
}

function paymentNet(entries: LedgerEntry[]): number {
  return entries.reduce((total, entry) => total + (entry.direction === "CREDIT" ? entry.amount : -entry.amount), 0);
}

function paymentLatencyMs(payment: Payment): number {
  return Math.max(0, Date.parse(payment.updatedAt) - Date.parse(payment.createdAt));
}

function average(values: number[]): number {
  if (values.length === 0) {
    return 0;
  }

  return Math.round(values.reduce((total, value) => total + value, 0) / values.length);
}

function percentile(values: number[], quantile: number): number {
  if (values.length === 0) {
    return 0;
  }

  const ordered = [...values].sort((left, right) => left - right);
  const index = Math.min(ordered.length - 1, Math.max(0, Math.ceil(ordered.length * quantile) - 1));
  return Math.round(ordered[index] ?? 0);
}

function rate(numerator: number, denominator: number): number {
  if (denominator === 0) {
    return 0;
  }

  return (numerator / denominator) * 100;
}

function ageMinutes(isoString: string, now: number): number {
  return Math.max(0, Math.round((now - Date.parse(isoString)) / 60_000));
}

function formatClock(value: number): string {
  return new Date(value).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  });
}
