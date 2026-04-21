import assert from "node:assert/strict";
import test from "node:test";
import { deriveAnalytics } from "./analytics.ts";
import type { LedgerEntry, Payment, ReconciliationItem, RetryAttempt, ReviewCase } from "./types.ts";

function payment(overrides: Partial<Payment> & Pick<Payment, "id" | "status" | "decision">): Payment {
  return {
    id: overrides.id,
    payerAccountId: overrides.payerAccountId ?? `payer-${overrides.id}`,
    payeeAccountId: overrides.payeeAccountId ?? `payee-${overrides.id}`,
    amount: overrides.amount ?? 10_000,
    currency: overrides.currency ?? "USD",
    status: overrides.status,
    idempotencyKey: overrides.idempotencyKey ?? `idem-${overrides.id}`,
    riskScore: overrides.riskScore ?? 0,
    decision: overrides.decision,
    createdAt: overrides.createdAt ?? "2026-04-21T13:30:00.000Z",
    updatedAt: overrides.updatedAt ?? "2026-04-21T13:40:00.000Z",
    reasonCodes: overrides.reasonCodes ?? [],
    events: overrides.events ?? []
  };
}

function ledgerEntry(overrides: Partial<LedgerEntry> & Pick<LedgerEntry, "id" | "paymentId">): LedgerEntry {
  return {
    id: overrides.id,
    journalId: overrides.journalId ?? `jnl-${overrides.paymentId}`,
    accountId: overrides.accountId ?? "account-1",
    direction: overrides.direction ?? "DEBIT",
    amount: overrides.amount ?? 10_000,
    currency: overrides.currency ?? "USD",
    paymentId: overrides.paymentId,
    createdAt: overrides.createdAt ?? "2026-04-21T13:40:00.000Z"
  };
}

test("deriveAnalytics reports ledger coverage, backlog age, and status rollups", () => {
  const fixedNow = Date.parse("2026-04-21T14:00:00.000Z");
  const realDateNow = Date.now;
  Date.now = () => fixedNow;

  try {
    const payments: Payment[] = [
      payment({
        id: "pay-1001",
        status: "SETTLED",
        decision: "APPROVE",
        riskScore: 18,
        createdAt: "2026-04-21T13:20:00.000Z",
        updatedAt: "2026-04-21T13:40:00.000Z"
      }),
      payment({
        id: "pay-1002",
        status: "RESERVED",
        decision: "REVIEW",
        riskScore: 82,
        createdAt: "2026-04-21T13:25:00.000Z",
        updatedAt: "2026-04-21T13:55:00.000Z"
      })
    ];

    const ledgerEntries: LedgerEntry[] = [
      ledgerEntry({
        id: "entry-1",
        paymentId: "pay-1001",
        direction: "DEBIT",
        amount: 10_000,
        accountId: "payer-1"
      }),
      ledgerEntry({
        id: "entry-2",
        paymentId: "pay-1001",
        direction: "CREDIT",
        amount: 10_000,
        accountId: "payee-1"
      })
    ];

    const reviewCases: ReviewCase[] = [
      {
        id: "review-1002",
        paymentId: "pay-1002",
        reason: "Velocity review pending.",
        status: "OPEN",
        assignedTo: "risk.reviewer@ledgerforge.local",
        createdAt: "2026-04-21T13:30:00.000Z"
      }
    ];

    const reconciliationItems: ReconciliationItem[] = [
      {
        id: "recon-1002",
        category: "MISMATCH",
        severity: "HIGH",
        paymentId: "pay-1002",
        createdAt: "2026-04-21T13:50:00.000Z",
        details: "Reserved payment is missing its balancing credit leg."
      }
    ];

    const retryAttempts: RetryAttempt[] = [
      {
        id: "retry-1002",
        paymentId: "pay-1002",
        fingerprint: "payer-1002:payee-1002:USD",
        attemptNumber: 2,
        status: "RESERVED",
        decision: "REVIEW",
        createdAt: "2026-04-21T13:55:00.000Z",
        outcome: "HELD",
        detail: "Held for manual review."
      }
    ];

    const analytics = deriveAnalytics({
      payments,
      ledgerEntries,
      reviewCases,
      reconciliationItems,
      retryAttempts
    });

    assert.equal(analytics.headline.approvalRate, 50);
    assert.equal(analytics.headline.reviewRate, 50);
    assert.equal(analytics.headline.settlementCoverageRate, 50);
    assert.equal(analytics.headline.anomalyRate, 50);
    assert.equal(analytics.headline.executedWithoutLedgerCount, 1);
    assert.equal(analytics.headline.oldestBacklogMinutes, 30);

    assert.equal(analytics.settlement.ledgerRequiredCount, 2);
    assert.equal(analytics.settlement.balancedCount, 1);
    assert.equal(analytics.settlement.missingLedgerCount, 1);

    assert.equal(analytics.backlog.length, 1);
    assert.equal(analytics.backlog[0]?.paymentId, "pay-1002");
    assert.equal(analytics.backlog[0]?.retryCount, 1);

    const reservedRow = analytics.statusRows.find((row) => row.status === "RESERVED");
    assert.ok(reservedRow);
    assert.equal(reservedRow?.openReviewCount, 1);
    assert.equal(reservedRow?.ledgerCoverageRate, 0);
  } finally {
    Date.now = realDateNow;
  }
});
