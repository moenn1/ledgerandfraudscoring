import { startTransition, useDeferredValue, useEffect, useMemo, useState } from "react";
import { loadAppData } from "./api";
import { AppData, LedgerEntry, Payment, ReconciliationItem, ReviewCase } from "./types";
import { asDate, asMoney, asPct, cx } from "./utils";

type ViewKey = "dashboard" | "payments" | "ledger" | "fraud";
type ReviewAction = "APPROVE" | "REJECT" | "ESCALATE";

interface NavItem {
  key: ViewKey;
  label: string;
  caption: string;
}

interface ReviewActionEvent {
  id: string;
  reviewCaseId: string;
  paymentId: string;
  action: ReviewAction;
  beforeStatus: ReviewCase["status"];
  afterStatus: ReviewCase["status"];
  actor: string;
  note: string;
  correlationId: string;
  idempotencyKey: string;
  createdAt: string;
}

const navItems: NavItem[] = [
  { key: "dashboard", label: "Mission Control", caption: "Live risk and settlement posture" },
  { key: "payments", label: "Payments", caption: "Timeline and state transitions" },
  { key: "ledger", label: "Ledger Explorer", caption: "Immutable journal traceability" },
  { key: "fraud", label: "Fraud + Recon", caption: "Review queue and anomalies" }
];

function App(): JSX.Element {
  const [view, setView] = useState<ViewKey>("dashboard");
  const [data, setData] = useState<AppData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedPaymentId, setSelectedPaymentId] = useState<string>("");
  const [query, setQuery] = useState("");
  const [reviewCases, setReviewCases] = useState<ReviewCase[]>([]);
  const [reviewActions, setReviewActions] = useState<ReviewActionEvent[]>([]);
  const [processingReviewCaseIds, setProcessingReviewCaseIds] = useState<string[]>([]);
  const deferredQuery = useDeferredValue(query);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    loadAppData()
      .then((result) => {
        if (cancelled) return;
        setData(result);
        setReviewCases(result.reviewCases);
        setSelectedPaymentId(result.payments[0]?.id ?? "");
      })
      .catch((loadError) => {
        if (cancelled) return;
        setError(loadError instanceof Error ? loadError.message : "Failed to load dashboard data.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const filteredPayments = useMemo(() => {
    if (!data) return [];
    const q = deferredQuery.trim().toLowerCase();
    if (!q) return data.payments;

    return data.payments.filter((payment) => {
      return (
        payment.id.toLowerCase().includes(q) ||
        payment.status.toLowerCase().includes(q) ||
        payment.payerAccountId.toLowerCase().includes(q) ||
        payment.payeeAccountId.toLowerCase().includes(q)
      );
    });
  }, [data, deferredQuery]);

  const selectedPayment = useMemo(() => {
    if (!data) return null;
    return data.payments.find((payment) => payment.id === selectedPaymentId) ?? data.payments[0] ?? null;
  }, [data, selectedPaymentId]);

  const selectedPaymentLedgerEntries = useMemo(() => {
    if (!data || !selectedPayment) return [];
    return data.ledgerEntries
      .filter((entry) => entry.paymentId === selectedPayment.id)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }, [data, selectedPayment]);

  const handleReviewAction = (reviewCaseId: string, action: ReviewAction): void => {
    if (processingReviewCaseIds.includes(reviewCaseId)) return;

    const targetCase = reviewCases.find((item) => item.id === reviewCaseId);
    if (!targetCase || targetCase.status !== "OPEN") return;

    const idempotencyKey = `review-${targetCase.id}-${action.toLowerCase()}`;
    if (reviewActions.some((event) => event.idempotencyKey === idempotencyKey)) return;

    const createdAt = new Date().toISOString();
    const afterStatus: ReviewCase["status"] =
      action === "APPROVE" ? "APPROVED" : action === "REJECT" ? "REJECTED" : targetCase.status;
    const nextOwner = action === "ESCALATE" ? "risk.lead@ledgerforge.local" : targetCase.assignedTo;

    const event: ReviewActionEvent = {
      id: `${targetCase.id}-${action.toLowerCase()}-${Date.now().toString(36)}`,
      reviewCaseId: targetCase.id,
      paymentId: targetCase.paymentId,
      action,
      beforeStatus: targetCase.status,
      afterStatus,
      actor: "operator.ui@ledgerforge.local",
      note:
        action === "APPROVE"
          ? "Case approved and removed from manual review queue."
          : action === "REJECT"
            ? "Case rejected and payment flagged for decline/follow-up."
            : "Case escalated to risk lead for secondary investigation.",
      correlationId: `corr-${targetCase.id}-${Date.now().toString(36)}`,
      idempotencyKey,
      createdAt
    };

    setProcessingReviewCaseIds((current) => [...current, reviewCaseId]);

    startTransition(() => {
      setReviewCases((current) =>
        current.map((item) =>
          item.id === reviewCaseId
            ? {
                ...item,
                status: afterStatus,
                assignedTo: nextOwner
              }
            : item
        )
      );
      setReviewActions((current) => [event, ...current]);
      setProcessingReviewCaseIds((current) => current.filter((id) => id !== reviewCaseId));
    });
  };

  const content = useMemo(() => {
    if (!data) return null;

    switch (view) {
      case "dashboard":
        return (
          <DashboardPage
            data={data}
            reviewCases={reviewCases}
            onJumpToPayments={() => {
              startTransition(() => setView("payments"));
            }}
          />
        );
      case "payments":
        return (
          <PaymentsPage
            payments={filteredPayments}
            selectedPayment={selectedPayment}
            selectedPaymentLedgerEntries={selectedPaymentLedgerEntries}
            query={query}
            onQueryChange={setQuery}
            onSelectPayment={setSelectedPaymentId}
          />
        );
      case "ledger":
        return <LedgerExplorerPage entries={data.ledgerEntries} />;
      case "fraud":
        return (
          <FraudConsolePage
            reviewCases={reviewCases}
            reviewActions={reviewActions}
            reconciliationItems={data.reconciliationItems}
            processingReviewCaseIds={processingReviewCaseIds}
            onReviewAction={handleReviewAction}
          />
        );
      default:
        return null;
    }
  }, [
    data,
    filteredPayments,
    processingReviewCaseIds,
    query,
    reviewActions,
    reviewCases,
    selectedPayment,
    selectedPaymentLedgerEntries,
    view
  ]);

  return (
    <div className="app-shell">
      <div className="ambient ambient-a" />
      <div className="ambient ambient-b" />

      <header className="topbar">
        <div>
          <p className="eyebrow">LedgerForge Payments</p>
          <h1>Operator Console</h1>
        </div>
        <div className="status-strip">
          <span className="pill">Source: {data ? "API (mock fallback armed)" : "Loading"}</span>
          <span className="pill">UTC {new Date().toISOString().slice(11, 19)}</span>
        </div>
      </header>

      <nav className="nav-grid">
        {navItems.map((item) => (
          <button
            key={item.key}
            className={cx("nav-tile", view === item.key && "is-active")}
            onClick={() => startTransition(() => setView(item.key))}
          >
            <span className="nav-label">{item.label}</span>
            <span className="nav-caption">{item.caption}</span>
          </button>
        ))}
      </nav>

      <main className="main-panel">
        {loading && <SkeletonState />}
        {!loading && error && <ErrorState message={error} />}
        {!loading && !error && content}
      </main>
    </div>
  );
}

function SkeletonState(): JSX.Element {
  return (
    <section className="panel">
      <p className="eyebrow">Bootstrapping view</p>
      <h2>Loading transaction intelligence...</h2>
      <div className="skeleton-grid">
        <div className="skeleton-card" />
        <div className="skeleton-card" />
        <div className="skeleton-card" />
        <div className="skeleton-card" />
      </div>
    </section>
  );
}

function ErrorState({ message }: { message: string }): JSX.Element {
  return (
    <section className="panel">
      <p className="eyebrow">Data load issue</p>
      <h2>Unable to render console data</h2>
      <p className="muted">{message}</p>
      <p className="muted">Check `VITE_API_BASE_URL`, backend availability, or network policies.</p>
    </section>
  );
}

function DashboardPage({
  data,
  reviewCases,
  onJumpToPayments
}: {
  data: AppData;
  reviewCases: ReviewCase[];
  onJumpToPayments: () => void;
}): JSX.Element {
  const healthClass = data.metrics.ledgerHealth === "HEALTHY" ? "good" : "warn";
  const recentPayments = data.payments.slice(0, 5);
  const openReviewCases = reviewCases.filter((item) => item.status === "OPEN");

  return (
    <section className="dashboard">
      <article className="panel animated">
        <p className="eyebrow">Realtime posture</p>
        <h2>Risk-adjusted payment flow is live</h2>
        <div className="metric-grid">
          <MetricCard label="Processed today" value={String(data.metrics.processedToday)} />
          <MetricCard label="Approve rate" value={asPct(data.metrics.approvedRate)} />
          <MetricCard label="Reject rate" value={asPct(data.metrics.rejectedRate)} />
          <MetricCard label="Review queue" value={String(openReviewCases.length)} />
          <MetricCard label="Anomalies" value={String(data.metrics.anomalyCount)} />
          <MetricCard label="P95 latency" value={`${data.metrics.p95LatencyMs} ms`} />
          <MetricCard label="Ledger health" value={data.metrics.ledgerHealth} tone={healthClass} />
        </div>
      </article>

      <div className="split-grid">
        <article className="panel animated stagger-1">
          <div className="panel-head">
            <div>
              <p className="eyebrow">Recent payments</p>
              <h3>Latest transaction stream</h3>
            </div>
            <button className="action-link" onClick={onJumpToPayments}>
              Open payment explorer
            </button>
          </div>
          <table className="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Status</th>
                <th>Amount</th>
                <th>Risk</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {recentPayments.map((payment) => (
                <tr key={payment.id}>
                  <td>{payment.id}</td>
                  <td>
                    <span className={cx("status-chip", statusTone(payment.status))}>{payment.status}</span>
                  </td>
                  <td>{asMoney(payment.amount, payment.currency)}</td>
                  <td>{payment.riskScore}</td>
                  <td>{asDate(payment.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </article>

        <article className="panel animated stagger-2">
          <p className="eyebrow">Manual review queue</p>
          <h3>Open cases</h3>
          <ul className="list">
            {openReviewCases.map((item) => (
              <li key={item.id} className="list-item">
                <div>
                  <strong>{item.paymentId}</strong>
                  <p>{item.reason}</p>
                </div>
                <div className="list-meta">
                  <span>{item.assignedTo}</span>
                  <span>{asDate(item.createdAt)}</span>
                </div>
              </li>
            ))}
            {openReviewCases.length === 0 && <li className="muted">No open manual review cases.</li>}
          </ul>
        </article>
      </div>
    </section>
  );
}

function PaymentsPage({
  payments,
  selectedPayment,
  selectedPaymentLedgerEntries,
  query,
  onQueryChange,
  onSelectPayment
}: {
  payments: Payment[];
  selectedPayment: Payment | null;
  selectedPaymentLedgerEntries: LedgerEntry[];
  query: string;
  onQueryChange: (value: string) => void;
  onSelectPayment: (paymentId: string) => void;
}): JSX.Element {
  const ledgerNet = useMemo(() => {
    return selectedPaymentLedgerEntries.reduce((acc, entry) => {
      return acc + (entry.direction === "CREDIT" ? entry.amount : -entry.amount);
    }, 0);
  }, [selectedPaymentLedgerEntries]);

  const isBalanced = selectedPaymentLedgerEntries.length > 0 && ledgerNet === 0;

  return (
    <section className="split-grid">
      <article className="panel animated">
        <div className="panel-head">
          <div>
            <p className="eyebrow">Payment feed</p>
            <h2>Search and inspect intents</h2>
          </div>
          <input
            className="search-input"
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="Search by id, status, or account"
          />
        </div>
        <div className="scroll-area">
          <table className="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Decision</th>
                <th>Status</th>
                <th>Amount</th>
                <th>Risk</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((payment) => (
                <tr
                  key={payment.id}
                  className={cx(selectedPayment?.id === payment.id && "row-selected")}
                  onClick={() => onSelectPayment(payment.id)}
                >
                  <td>{payment.id}</td>
                  <td>{payment.decision}</td>
                  <td>{payment.status}</td>
                  <td>{asMoney(payment.amount, payment.currency)}</td>
                  <td>{payment.riskScore}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </article>

      <article className="panel animated stagger-1">
        {!selectedPayment ? (
          <p className="muted">Select a payment to inspect timeline and risk reasons.</p>
        ) : (
          <>
            <p className="eyebrow">Payment detail</p>
            <h3>{selectedPayment.id}</h3>
            <div className="detail-grid">
              <Detail label="Payer">{selectedPayment.payerAccountId}</Detail>
              <Detail label="Payee">{selectedPayment.payeeAccountId}</Detail>
              <Detail label="Amount">{asMoney(selectedPayment.amount, selectedPayment.currency)}</Detail>
              <Detail label="Idempotency">{selectedPayment.idempotencyKey}</Detail>
            </div>
            <div className="tag-row">
              {selectedPayment.reasonCodes.map((reason) => (
                <span key={reason} className="tag">
                  {reason}
                </span>
              ))}
            </div>

            <h4>State timeline</h4>
            <ol className="timeline">
              {selectedPayment.events.map((item) => (
                <li key={item.id}>
                  <div>
                    <strong>{item.status}</strong>
                    <p>
                      {item.note} · actor: {item.actor}
                    </p>
                  </div>
                  <span>{asDate(item.timestamp)}</span>
                </li>
              ))}
            </ol>

            <h4>Ledger legs</h4>
            {selectedPaymentLedgerEntries.length === 0 ? (
              <p className="muted">No ledger entries posted yet for this payment.</p>
            ) : (
              <>
                <div className="ledger-check-row">
                  <span className={cx("tag", isBalanced ? "severity-low" : "severity-high")}>
                    {isBalanced ? "Balanced journal" : "Drift detected"}
                  </span>
                  <span className="muted">Net {asMoney(ledgerNet, selectedPayment.currency)}</span>
                </div>
                <div className="scroll-area scroll-area-sm">
                  <table className="data-table data-table-tight">
                    <thead>
                      <tr>
                        <th>Journal</th>
                        <th>Account</th>
                        <th>Direction</th>
                        <th>Amount</th>
                        <th>Posted</th>
                      </tr>
                    </thead>
                    <tbody>
                      {selectedPaymentLedgerEntries.map((entry) => (
                        <tr key={entry.id}>
                          <td>{entry.journalId}</td>
                          <td>{entry.accountId}</td>
                          <td>{entry.direction}</td>
                          <td>{asMoney(entry.amount, entry.currency)}</td>
                          <td>{asDate(entry.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </>
        )}
      </article>
    </section>
  );
}

function LedgerExplorerPage({ entries }: { entries: LedgerEntry[] }): JSX.Element {
  const [accountFilter, setAccountFilter] = useState("");
  const [directionFilter, setDirectionFilter] = useState<"ALL" | "DEBIT" | "CREDIT">("ALL");

  const filteredEntries = useMemo(() => {
    return entries.filter((entry) => {
      const accountMatch = accountFilter ? entry.accountId.toLowerCase().includes(accountFilter.toLowerCase()) : true;
      const directionMatch = directionFilter === "ALL" ? true : entry.direction === directionFilter;
      return accountMatch && directionMatch;
    });
  }, [accountFilter, directionFilter, entries]);

  const balanceByAccount = useMemo(() => {
    const accountTotals = new Map<string, number>();

    for (const entry of filteredEntries) {
      const signed = entry.direction === "DEBIT" ? -entry.amount : entry.amount;
      accountTotals.set(entry.accountId, (accountTotals.get(entry.accountId) ?? 0) + signed);
    }

    return [...accountTotals.entries()].sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]));
  }, [filteredEntries]);

  return (
    <section className="split-grid">
      <article className="panel animated">
        <div className="panel-head">
          <div>
            <p className="eyebrow">Immutable journal</p>
            <h2>Ledger entry explorer</h2>
          </div>
          <div className="inline-filters">
            <input
              className="search-input"
              placeholder="Filter account"
              value={accountFilter}
              onChange={(event) => setAccountFilter(event.target.value)}
            />
            <select value={directionFilter} onChange={(event) => setDirectionFilter(event.target.value as typeof directionFilter)}>
              <option value="ALL">All</option>
              <option value="DEBIT">Debit</option>
              <option value="CREDIT">Credit</option>
            </select>
          </div>
        </div>
        <div className="scroll-area">
          <table className="data-table">
            <thead>
              <tr>
                <th>Journal</th>
                <th>Payment</th>
                <th>Account</th>
                <th>Direction</th>
                <th>Amount</th>
                <th>At</th>
              </tr>
            </thead>
            <tbody>
              {filteredEntries.map((entry) => (
                <tr key={entry.id}>
                  <td>{entry.journalId}</td>
                  <td>{entry.paymentId}</td>
                  <td>{entry.accountId}</td>
                  <td>{entry.direction}</td>
                  <td>{asMoney(entry.amount, entry.currency)}</td>
                  <td>{asDate(entry.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </article>

      <article className="panel animated stagger-1">
        <p className="eyebrow">Balance projection</p>
        <h3>Derived by account</h3>
        <ul className="list">
          {balanceByAccount.map(([accountId, projected]) => (
            <li key={accountId} className="list-item">
              <strong>{accountId}</strong>
              <span className={cx(projected >= 0 ? "metric-positive" : "metric-negative")}>{asMoney(projected)}</span>
            </li>
          ))}
        </ul>
      </article>
    </section>
  );
}

function FraudConsolePage({
  reviewCases,
  reviewActions,
  reconciliationItems,
  processingReviewCaseIds,
  onReviewAction
}: {
  reviewCases: ReviewCase[];
  reviewActions: ReviewActionEvent[];
  reconciliationItems: ReconciliationItem[];
  processingReviewCaseIds: string[];
  onReviewAction: (reviewCaseId: string, action: ReviewAction) => void;
}): JSX.Element {
  const recentActions = reviewActions.slice(0, 8);

  return (
    <section className="split-grid">
      <article className="panel animated">
        <p className="eyebrow">Fraud operations</p>
        <h2>Manual review console</h2>
        <table className="data-table">
          <thead>
            <tr>
              <th>Case</th>
              <th>Payment</th>
              <th>Status</th>
              <th>Reason</th>
              <th>Owner</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {reviewCases.map((item) => {
              const isBusy = processingReviewCaseIds.includes(item.id);
              const isOpen = item.status === "OPEN";

              return (
                <tr key={item.id}>
                  <td>{item.id}</td>
                  <td>{item.paymentId}</td>
                  <td>
                    <span className={cx("status-chip", reviewStatusTone(item.status))}>{item.status}</span>
                  </td>
                  <td>{item.reason}</td>
                  <td>{item.assignedTo}</td>
                  <td>
                    {isOpen ? (
                      <div className="table-action-row">
                        <button
                          className="table-action table-action-approve"
                          disabled={isBusy}
                          onClick={() => onReviewAction(item.id, "APPROVE")}
                        >
                          Approve
                        </button>
                        <button
                          className="table-action table-action-reject"
                          disabled={isBusy}
                          onClick={() => onReviewAction(item.id, "REJECT")}
                        >
                          Reject
                        </button>
                        <button
                          className="table-action table-action-escalate"
                          disabled={isBusy}
                          onClick={() => onReviewAction(item.id, "ESCALATE")}
                        >
                          Escalate
                        </button>
                      </div>
                    ) : (
                      <span className="muted">Closed</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </article>

      <article className="panel animated stagger-1">
        <p className="eyebrow">Reconciliation</p>
        <h3>Anomalies and repair candidates</h3>
        <ul className="list">
          {reconciliationItems.map((item) => (
            <li key={item.id} className="list-item">
              <div>
                <strong>
                  {item.category} · {item.paymentId}
                </strong>
                <p>{item.details}</p>
              </div>
              <div className="list-meta">
                <span className={cx("tag", severityTone(item.severity))}>{item.severity}</span>
                <span>{asDate(item.createdAt)}</span>
              </div>
            </li>
          ))}
        </ul>

        <div className="divider" />

        <p className="eyebrow">Review audit</p>
        <h3>Immutable decision trail</h3>
        {recentActions.length === 0 ? (
          <p className="muted">No review actions captured in this session.</p>
        ) : (
          <ol className="timeline timeline-compact">
            {recentActions.map((event) => (
              <li key={event.id}>
                <div>
                  <strong>
                    {event.action} · {event.paymentId}
                  </strong>
                  <p>
                    {event.note} ({event.beforeStatus} → {event.afterStatus})
                  </p>
                  <p>
                    corr: {event.correlationId} · idem: {event.idempotencyKey}
                  </p>
                </div>
                <span>{asDate(event.createdAt)}</span>
              </li>
            ))}
          </ol>
        )}
      </article>
    </section>
  );
}

function MetricCard({
  label,
  value,
  tone
}: {
  label: string;
  value: string;
  tone?: "good" | "warn";
}): JSX.Element {
  return (
    <div className={cx("metric-card", tone && `metric-${tone}`)}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function Detail({ label, children }: { label: string; children: React.ReactNode }): JSX.Element {
  return (
    <div className="detail">
      <span>{label}</span>
      <strong>{children}</strong>
    </div>
  );
}

function statusTone(status: string): string {
  if (["SETTLED", "CAPTURED", "APPROVED"].includes(status)) return "status-good";
  if (["REJECTED", "REVERSED"].includes(status)) return "status-bad";
  return "status-neutral";
}

function reviewStatusTone(status: ReviewCase["status"]): string {
  if (status === "APPROVED") return "status-good";
  if (status === "REJECTED") return "status-bad";
  return "status-neutral";
}

function severityTone(level: ReconciliationItem["severity"]): string {
  if (level === "HIGH") return "severity-high";
  if (level === "MEDIUM") return "severity-medium";
  return "severity-low";
}

export default App;
