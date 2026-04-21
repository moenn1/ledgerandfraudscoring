import assert from "node:assert/strict";
import test from "node:test";
import { deriveAuditEntries, deriveRepairRecommendations, deriveRetryAttempts } from "./investigation.ts";
import type { LedgerEntry, Payment, ReconciliationItem, ReviewCase } from "./types.ts";

function payment(overrides: Partial<Payment> & Pick<Payment, "id" | "status" | "decision">): Payment {
  return {
    id: overrides.id,
    payerAccountId: overrides.payerAccountId ?? "payer-shared",
    payeeAccountId: overrides.payeeAccountId ?? "payee-shared",
    amount: overrides.amount ?? 4_200,
    currency: overrides.currency ?? "USD",
    status: overrides.status,
    idempotencyKey: overrides.idempotencyKey ?? `idem-${overrides.id}`,
    riskScore: overrides.riskScore ?? 0,
    decision: overrides.decision,
    createdAt: overrides.createdAt ?? "2026-04-21T13:00:00.000Z",
    updatedAt: overrides.updatedAt ?? "2026-04-21T13:05:00.000Z",
    reasonCodes: overrides.reasonCodes ?? [],
    events: overrides.events ?? []
  };
}

test("deriveRetryAttempts groups corridor retries and keeps the newest attempt first", () => {
  const attempts = deriveRetryAttempts([
    payment({
      id: "pay-1",
      status: "APPROVED",
      decision: "REVIEW",
      createdAt: "2026-04-21T13:00:00.000Z"
    }),
    payment({
      id: "pay-2",
      status: "CAPTURED",
      decision: "APPROVE",
      createdAt: "2026-04-21T13:10:00.000Z"
    }),
    payment({
      id: "pay-3",
      payerAccountId: "payer-other",
      payeeAccountId: "payee-other",
      status: "SETTLED",
      decision: "APPROVE",
      createdAt: "2026-04-21T13:15:00.000Z"
    })
  ]);

  assert.equal(attempts.length, 2);
  assert.equal(attempts[0]?.paymentId, "pay-2");
  assert.equal(attempts[0]?.attemptNumber, 2);
  assert.equal(attempts[0]?.outcome, "PROGRESSED");
  assert.equal(attempts[1]?.paymentId, "pay-1");
  assert.equal(attempts[1]?.outcome, "HELD");
});

test("deriveRepairRecommendations preserves append-only guardrails and review context", () => {
  const payments: Payment[] = [
    payment({ id: "pay-1", status: "RESERVED", decision: "REVIEW" }),
    payment({ id: "pay-2", status: "REJECTED", decision: "REJECT" })
  ];

  const reviewCases: ReviewCase[] = [
    {
      id: "review-1",
      paymentId: "pay-1",
      reason: "Manual review required.",
      status: "OPEN",
      assignedTo: "risk@ledgerforge.local",
      createdAt: "2026-04-21T13:03:00.000Z"
    }
  ];

  const recommendations = deriveRepairRecommendations(
    [
      {
        id: "recon-1",
        category: "MISMATCH",
        severity: "HIGH",
        paymentId: "pay-1",
        createdAt: "2026-04-21T13:04:00.000Z",
        details: "Missing balancing credit."
      },
      {
        id: "recon-2",
        category: "DUPLICATE_ATTEMPT",
        severity: "LOW",
        paymentId: "pay-2",
        createdAt: "2026-04-21T13:06:00.000Z",
        details: "Duplicate attempt blocked."
      }
    ],
    payments,
    reviewCases
  );

  assert.equal(recommendations[0]?.owner, "ledger.ops@ledgerforge.local");
  assert.match(recommendations[0]?.guardrail ?? "", /Never mutate existing journal rows\./);
  assert.match(recommendations[0]?.guardrail ?? "", /Keep the linked review case open/);
  assert.equal(recommendations[1]?.owner, "risk.ops@ledgerforge.local");
  assert.match(recommendations[1]?.guardrail ?? "", /Treat the ledger as the source of truth/);
});

test("deriveAuditEntries merges payment, ledger, review, and recon evidence in reverse time order", () => {
  const payments: Payment[] = [
    payment({
      id: "pay-1",
      status: "CAPTURED",
      decision: "APPROVE",
      events: [
        {
          id: "event-1",
          type: "captured",
          status: "CAPTURED",
          timestamp: "2026-04-21T13:02:00.000Z",
          actor: "payments-service",
          note: "Capture completed."
        }
      ]
    })
  ];

  const ledgerEntries: LedgerEntry[] = [
    {
      id: "ledger-1",
      journalId: "jnl-pay-1",
      accountId: "cash-clearing",
      direction: "CREDIT",
      amount: 4_200,
      currency: "USD",
      paymentId: "pay-1",
      createdAt: "2026-04-21T13:03:00.000Z"
    }
  ];

  const reviewCases: ReviewCase[] = [
    {
      id: "review-1",
      paymentId: "pay-1",
      reason: "Secondary verification complete.",
      status: "APPROVED",
      assignedTo: "risk@ledgerforge.local",
      createdAt: "2026-04-21T13:04:00.000Z"
    }
  ];

  const reconciliationItems: ReconciliationItem[] = [
    {
      id: "recon-1",
      category: "MISSING_EVENT",
      severity: "MEDIUM",
      paymentId: "pay-1",
      createdAt: "2026-04-21T13:05:00.000Z",
      details: "Outbox replay recovered the missing event."
    }
  ];

  const auditEntries = deriveAuditEntries(payments, ledgerEntries, reviewCases, reconciliationItems);

  assert.equal(auditEntries.length, 4);
  assert.deepEqual(
    auditEntries.map((entry) => entry.source),
    ["RECON", "REVIEW", "LEDGER", "PAYMENT"]
  );
  assert.equal(auditEntries[0]?.timestamp, "2026-04-21T13:05:00.000Z");
  assert.equal(auditEntries[3]?.title, "Payment captured");
});
