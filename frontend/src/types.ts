export type PaymentStatus =
  | "CREATED"
  | "VALIDATED"
  | "RISK_SCORING"
  | "APPROVED"
  | "RESERVED"
  | "CAPTURED"
  | "SETTLED"
  | "REJECTED"
  | "REVERSED"
  | "CHARGEBACK"
  | "REFUNDED"
  | "CANCELLED";

export type Decision = "APPROVE" | "REVIEW" | "REJECT";

export interface PaymentEvent {
  id: string;
  type: string;
  status: PaymentStatus;
  timestamp: string;
  actor: string;
  note: string;
}

export interface LedgerEntry {
  id: string;
  journalId: string;
  accountId: string;
  direction: "DEBIT" | "CREDIT";
  amount: number;
  currency: string;
  paymentId: string;
  createdAt: string;
}

export interface Payment {
  id: string;
  payerAccountId: string;
  payeeAccountId: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  idempotencyKey: string;
  riskScore: number;
  decision: Decision;
  createdAt: string;
  updatedAt: string;
  reasonCodes: string[];
  events: PaymentEvent[];
}

export interface ReviewCase {
  id: string;
  paymentId: string;
  reason: string;
  status: "OPEN" | "APPROVED" | "REJECTED";
  assignedTo: string;
  createdAt: string;
}

export interface ReconciliationItem {
  id: string;
  category: "MISMATCH" | "MISSING_EVENT" | "DUPLICATE_ATTEMPT";
  severity: "LOW" | "MEDIUM" | "HIGH";
  paymentId: string;
  createdAt: string;
  details: string;
}

export type AuditSource = "PAYMENT" | "LEDGER" | "REVIEW" | "RECON" | "SESSION";

export interface AuditEntry {
  id: string;
  paymentId: string;
  timestamp: string;
  actor: string;
  source: AuditSource;
  title: string;
  detail: string;
}

export type RetryOutcome = "PROGRESSED" | "HELD" | "BLOCKED";

export interface RetryAttempt {
  id: string;
  paymentId: string;
  fingerprint: string;
  attemptNumber: number;
  status: PaymentStatus;
  decision: Decision;
  createdAt: string;
  outcome: RetryOutcome;
  detail: string;
}

export interface RepairRecommendation {
  id: string;
  anomalyId: string;
  paymentId: string;
  title: string;
  detail: string;
  owner: string;
  urgency: ReconciliationItem["severity"];
  guardrail: string;
}

export interface DashboardMetrics {
  processedToday: number;
  approvedRate: number;
  rejectedRate: number;
  reviewQueueCount: number;
  ledgerHealth: "HEALTHY" | "DEGRADED";
  p95LatencyMs: number;
  anomalyCount: number;
}

export interface AppData {
  metrics: DashboardMetrics;
  payments: Payment[];
  ledgerEntries: LedgerEntry[];
  reviewCases: ReviewCase[];
  reconciliationItems: ReconciliationItem[];
  auditEntries: AuditEntry[];
  retryAttempts: RetryAttempt[];
  repairRecommendations: RepairRecommendation[];
}

export type DataSourceMode = "LIVE" | "HYBRID" | "MOCK";
export type ReviewActionMode = "API" | "LOCAL" | "READ_ONLY";

export interface AppLoadMeta {
  sourceMode: DataSourceMode;
  sourceLabel: string;
  reviewActionMode: ReviewActionMode;
  warnings: string[];
}

export interface AppLoadResult {
  data: AppData;
  meta: AppLoadMeta;
}
