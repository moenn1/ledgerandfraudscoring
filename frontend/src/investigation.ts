import {
  AuditEntry,
  LedgerEntry,
  Payment,
  ReconciliationItem,
  RepairRecommendation,
  RetryAttempt,
  RetryOutcome,
  ReviewCase
} from "./types";

export function deriveAuditEntries(
  payments: Payment[],
  ledgerEntries: LedgerEntry[],
  reviewCases: ReviewCase[],
  reconciliationItems: ReconciliationItem[]
): AuditEntry[] {
  const auditEntries: AuditEntry[] = [];

  for (const payment of payments) {
    for (const event of payment.events) {
      auditEntries.push({
        id: `audit-${event.id}`,
        paymentId: payment.id,
        timestamp: event.timestamp,
        actor: event.actor,
        source: "PAYMENT",
        title: `Payment ${event.status.toLowerCase()}`,
        detail: event.note
      });
    }
  }

  for (const entry of ledgerEntries) {
    auditEntries.push({
      id: `audit-ledger-${entry.id}`,
      paymentId: entry.paymentId,
      timestamp: entry.createdAt,
      actor: "ledger",
      source: "LEDGER",
      title: `Ledger ${entry.direction.toLowerCase()} ${entry.accountId}`,
      detail: `Journal ${entry.journalId} posted ${entry.amount} cents in ${entry.currency}.`
    });
  }

  for (const reviewCase of reviewCases) {
    auditEntries.push({
      id: `audit-review-${reviewCase.id}`,
      paymentId: reviewCase.paymentId,
      timestamp: reviewCase.createdAt,
      actor: reviewCase.assignedTo,
      source: "REVIEW",
      title: `Manual review ${reviewCase.status.toLowerCase()}`,
      detail: reviewCase.reason
    });
  }

  for (const item of reconciliationItems) {
    auditEntries.push({
      id: `audit-recon-${item.id}`,
      paymentId: item.paymentId,
      timestamp: item.createdAt,
      actor: "reconciliation-engine",
      source: "RECON",
      title: `Reconciliation ${item.category.toLowerCase()}`,
      detail: item.details
    });
  }

  return auditEntries.sort((left, right) => right.timestamp.localeCompare(left.timestamp));
}

export function deriveRetryAttempts(payments: Payment[]): RetryAttempt[] {
  const groups = new Map<string, Payment[]>();

  for (const payment of payments) {
    const fingerprint = paymentFingerprint(payment);
    const current = groups.get(fingerprint) ?? [];
    current.push(payment);
    groups.set(fingerprint, current);
  }

  const retryAttempts: RetryAttempt[] = [];

  for (const [fingerprint, group] of groups.entries()) {
    const ordered = [...group].sort((left, right) => left.createdAt.localeCompare(right.createdAt));
    if (ordered.length <= 1) {
      continue;
    }

    ordered.forEach((payment, index) => {
      retryAttempts.push({
        id: `retry-${payment.id}`,
        paymentId: payment.id,
        fingerprint,
        attemptNumber: index + 1,
        status: payment.status,
        decision: payment.decision,
        createdAt: payment.createdAt,
        outcome: outcomeForPayment(payment),
        detail: retryDetail(payment, index + 1, ordered.length)
      });
    });
  }

  return retryAttempts.sort((left, right) => right.createdAt.localeCompare(left.createdAt));
}

export function deriveRepairRecommendations(
  reconciliationItems: ReconciliationItem[],
  payments: Payment[],
  reviewCases: ReviewCase[]
): RepairRecommendation[] {
  const paymentsById = new Map(payments.map((payment) => [payment.id, payment]));
  const openReviewPayments = new Set(
    reviewCases.filter((reviewCase) => reviewCase.status === "OPEN").map((reviewCase) => reviewCase.paymentId)
  );

  return reconciliationItems.map((item) => {
    const payment = paymentsById.get(item.paymentId);
    const status = payment?.status ?? "UNKNOWN";
    const reviewGuard = openReviewPayments.has(item.paymentId)
      ? "Keep the linked review case open until the repair evidence is attached."
      : "Append evidence to the audit trail before closing the anomaly.";

    if (item.category === "MISMATCH") {
      return {
        id: `repair-${item.id}`,
        anomalyId: item.id,
        paymentId: item.paymentId,
        title: "Open compensating-entry investigation",
        detail: `Compare the ${status.toLowerCase()} payment against ledger legs and stage an append-only correction only after root cause is confirmed.`,
        owner: "ledger.ops@ledgerforge.local",
        urgency: item.severity,
        guardrail: `${reviewGuard} Never mutate existing journal rows.`
      };
    }

    if (item.category === "MISSING_EVENT") {
      return {
        id: `repair-${item.id}`,
        anomalyId: item.id,
        paymentId: item.paymentId,
        title: "Replay downstream event evidence",
        detail: `Validate that the ${status.toLowerCase()} payment is balanced in the ledger, then replay the missing notification or outbox emission.`,
        owner: "platform.events@ledgerforge.local",
        urgency: item.severity,
        guardrail: `${reviewGuard} Use correlation ids from the original payment before replaying.`
      };
    }

    return {
      id: `repair-${item.id}`,
      anomalyId: item.id,
      paymentId: item.paymentId,
      title: "Confirm duplicate protection and close review",
      detail: `Verify the duplicate attempt was stopped by idempotency controls and that no second credit posted for the ${status.toLowerCase()} payment.`,
      owner: "risk.ops@ledgerforge.local",
      urgency: item.severity,
      guardrail: `${reviewGuard} Treat the ledger as the source of truth when clearing the signal.`
    };
  });
}

function paymentFingerprint(payment: Payment): string {
  return [payment.payerAccountId, payment.payeeAccountId, payment.currency].join(":");
}

function outcomeForPayment(payment: Payment): RetryOutcome {
  if (payment.status === "REJECTED" || payment.status === "CANCELLED" || payment.status === "REVERSED") {
    return "BLOCKED";
  }
  if (payment.decision === "REVIEW" || payment.status === "RISK_SCORING" || payment.status === "APPROVED") {
    return "HELD";
  }
  return "PROGRESSED";
}

function retryDetail(payment: Payment, attemptNumber: number, totalAttempts: number): string {
  const suffix =
    payment.decision === "REVIEW"
      ? "was held for manual review."
      : payment.status === "REJECTED"
        ? "was stopped by policy."
        : payment.status === "CANCELLED"
          ? "was cancelled before execution."
          : "progressed through execution.";

  return `Attempt ${attemptNumber} of ${totalAttempts} reused the payer/payee corridor and ${suffix}`;
}
