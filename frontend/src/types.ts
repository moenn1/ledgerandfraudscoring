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
  | "REFUNDED";

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
}
