import type {
  AppData,
  Decision,
  LedgerEntry,
  Payment,
  PaymentEvent,
  PaymentStatus,
  ReconciliationItem,
  ReviewCase
} from "./types.ts";
import { deriveAuditEntries, deriveRepairRecommendations, deriveRetryAttempts } from "./investigation.ts";

function iso(minutesAgo: number): string {
  return new Date(Date.now() - minutesAgo * 60_000).toISOString();
}

function event(
  id: string,
  status: PaymentStatus,
  minutesAgo: number,
  note: string,
  actor = "orchestrator"
): PaymentEvent {
  return {
    id,
    type: status.toLowerCase(),
    status,
    timestamp: iso(minutesAgo),
    actor,
    note
  };
}

function payment(
  id: string,
  amount: number,
  status: PaymentStatus,
  score: number,
  decision: Decision,
  minutesAgo: number,
  reasons: string[]
): Payment {
  const createdAt = iso(minutesAgo + 8);
  const updatedAt = iso(minutesAgo);
  const events: PaymentEvent[] = [
    event(`${id}-created`, "CREATED", minutesAgo + 8, "Intent submitted by API client."),
    event(`${id}-validated`, "VALIDATED", minutesAgo + 7, "Currency, account state, and amount validated."),
    event(`${id}-risk`, "RISK_SCORING", minutesAgo + 6, `Risk score computed (${score}).`)
  ];

  if (decision !== "REJECT") {
    events.push(event(`${id}-approved`, "APPROVED", minutesAgo + 5, "Payment approved by risk policy."));
    events.push(event(`${id}-reserved`, "RESERVED", minutesAgo + 4, "Funds reserved in payer account."));
  }
  if (status === "CAPTURED" || status === "SETTLED" || status === "REFUNDED") {
    events.push(event(`${id}-captured`, "CAPTURED", minutesAgo + 3, "Capture completed."));
  }
  if (status === "SETTLED" || status === "REFUNDED") {
    events.push(event(`${id}-settled`, "SETTLED", minutesAgo + 2, "Net settlement posted."));
  }
  if (status === "REFUNDED") {
    events.push(event(`${id}-refunded`, "REFUNDED", minutesAgo + 1, "Refund initiated by operator."));
  }
  if (status === "REJECTED") {
    events.push(
      event(`${id}-rejected`, "REJECTED", minutesAgo + 2, "Payment rejected by fraud threshold.", "fraud-engine")
    );
  }

  return {
    id,
    payerAccountId: `acc-payer-${id.slice(-3)}`,
    payeeAccountId: `acc-payee-${id.slice(-3)}`,
    amount,
    currency: "USD",
    status,
    idempotencyKey: `idem-${id}`,
    riskScore: score,
    decision,
    createdAt,
    updatedAt,
    reasonCodes: reasons,
    events
  };
}

const payments: Payment[] = [
  payment("pay-1001", 125_00, "SETTLED", 22, "APPROVE", 95, ["trusted_device"]),
  payment("pay-1002", 6_600_00, "REJECTED", 88, "REJECT", 80, ["ip_country_mismatch", "new_device_high_value"]),
  payment("pay-1003", 94_20, "CAPTURED", 38, "APPROVE", 73, ["first_time_payee"]),
  {
    ...payment("pay-1004", 11_000_00, "RESERVED", 64, "REVIEW", 59, ["velocity_1m_exceeded", "declined_attempts"]),
    payerAccountId: "acc-payer-risk-ops",
    payeeAccountId: "acc-payee-merchant-330"
  },
  payment("pay-1005", 240_00, "SETTLED", 31, "APPROVE", 45, ["amount_near_user_baseline"]),
  payment("pay-1006", 520_00, "REFUNDED", 18, "APPROVE", 33, ["trusted_pattern"]),
  {
    ...payment("pay-1007", 8_450_00, "RISK_SCORING", 55, "REVIEW", 20, ["new_account_high_value"]),
    payerAccountId: "acc-payer-risk-ops",
    payeeAccountId: "acc-payee-merchant-330"
  },
  {
    ...payment("pay-1008", 2_150_00, "APPROVED", 41, "REVIEW", 12, ["ip_velocity_spike"]),
    payerAccountId: "acc-payer-risk-ops",
    payeeAccountId: "acc-payee-merchant-330"
  }
];

const ledgerEntries: LedgerEntry[] = payments.flatMap((p) => {
  const postedAt = p.updatedAt;
  const base: LedgerEntry[] = [
    {
      id: `${p.id}-d1`,
      journalId: `jnl-${p.id}`,
      accountId: p.payerAccountId,
      direction: "DEBIT",
      amount: p.amount,
      currency: p.currency,
      paymentId: p.id,
      createdAt: postedAt
    },
    {
      id: `${p.id}-c1`,
      journalId: `jnl-${p.id}`,
      accountId: p.payeeAccountId,
      direction: "CREDIT",
      amount: Math.round(p.amount * 0.97),
      currency: p.currency,
      paymentId: p.id,
      createdAt: postedAt
    },
    {
      id: `${p.id}-c2`,
      journalId: `jnl-${p.id}`,
      accountId: "acc-platform-revenue",
      direction: "CREDIT",
      amount: p.amount - Math.round(p.amount * 0.97),
      currency: p.currency,
      paymentId: p.id,
      createdAt: postedAt
    }
  ];

  return p.status === "REJECTED" ? base.slice(0, 1) : base;
});

const reviewCases: ReviewCase[] = [
  {
    id: "review-5001",
    paymentId: "pay-1004",
    reason: "Velocity threshold exceeded with high amount.",
    status: "OPEN",
    assignedTo: "risk.reviewer@ledgerforge.local",
    createdAt: iso(55)
  },
  {
    id: "review-5002",
    paymentId: "pay-1007",
    reason: "New account created less than 24h before transfer.",
    status: "OPEN",
    assignedTo: "risk.lead@ledgerforge.local",
    createdAt: iso(18)
  },
  {
    id: "review-5003",
    paymentId: "pay-1008",
    reason: "Rapid retries from rotated IP ranges.",
    status: "OPEN",
    assignedTo: "ops.risk@ledgerforge.local",
    createdAt: iso(11)
  }
];

const reconciliationItems: ReconciliationItem[] = [
  {
    id: "recon-7001",
    category: "MISMATCH",
    severity: "HIGH",
    paymentId: "pay-1002",
    createdAt: iso(76),
    details: "Unbalanced debit-only journal for rejected payment requires compensating entry review."
  },
  {
    id: "recon-7002",
    category: "MISSING_EVENT",
    severity: "MEDIUM",
    paymentId: "pay-1006",
    createdAt: iso(28),
    details: "Refund authorization event not found in outbox replay window."
  },
  {
    id: "recon-7003",
    category: "DUPLICATE_ATTEMPT",
    severity: "LOW",
    paymentId: "pay-1008",
    createdAt: iso(9),
    details: "Second capture attempt blocked by idempotency key, no double-credit detected."
  }
];

export const mockData: AppData = {
  metrics: {
    processedToday: 482,
    approvedRate: 91.7,
    rejectedRate: 3.4,
    reviewQueueCount: reviewCases.filter((r) => r.status === "OPEN").length,
    ledgerHealth: "DEGRADED",
    p95LatencyMs: 264,
    anomalyCount: reconciliationItems.length
  },
  payments,
  ledgerEntries,
  reviewCases,
  reconciliationItems,
  auditEntries: deriveAuditEntries(payments, ledgerEntries, reviewCases, reconciliationItems),
  retryAttempts: deriveRetryAttempts(payments),
  repairRecommendations: deriveRepairRecommendations(reconciliationItems, payments, reviewCases)
};
