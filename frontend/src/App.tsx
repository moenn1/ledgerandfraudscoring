import { startTransition, useDeferredValue, useEffect, useMemo, useState, type ChangeEvent, type ReactNode } from "react";
import { deriveAnalytics, type AnalyticsData } from "./analytics";
import { loadAppData, REVIEW_ACTOR_ID, submitReviewDecision } from "./api";
import {
  AppData,
  AppLoadResult,
  AppLoadMeta,
  AuditEntry,
  LedgerEntry,
  Payment,
  ReconciliationItem,
  RepairRecommendation,
  RetryAttempt,
  ReviewCase
} from "./types";
import { asDate, asMoney, asPct, cx } from "./utils";

type ViewKey = "dashboard" | "analytics" | "payments" | "ledger" | "fraud";
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
  { key: "analytics", label: "Analytics", caption: "Trends, backlog, and reporting" },
  { key: "payments", label: "Payments", caption: "Timeline and state transitions" },
  { key: "ledger", label: "Ledger Explorer", caption: "Immutable journal traceability" },
  { key: "fraud", label: "Fraud + Recon", caption: "Review queue and anomalies" }
];

function App(): JSX.Element {
  const [view, setView] = useState<ViewKey>("dashboard");
  const [data, setData] = useState<AppData | null>(null);
  const [loadMeta, setLoadMeta] = useState<AppLoadMeta | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedPaymentId, setSelectedPaymentId] = useState<string>("");
  const [query, setQuery] = useState("");
  const [reviewCases, setReviewCases] = useState<ReviewCase[]>([]);
  const [reviewActions, setReviewActions] = useState<ReviewActionEvent[]>([]);
  const [processingReviewCaseIds, setProcessingReviewCaseIds] = useState<string[]>([]);
  const deferredQuery = useDeferredValue(query);

  const applyLoadedState = (result: AppLoadResult): void => {
    setData(result.data);
    setLoadMeta(result.meta);
    setReviewCases(result.data.reviewCases);
    setSelectedPaymentId((current) => {
      if (current && result.data.payments.some((payment) => payment.id === current)) {
        return current;
      }
      return result.data.payments[0]?.id ?? "";
    });
  };

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    loadAppData()
      .then((result) => {
        if (cancelled) return;
        applyLoadedState(result);
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

  const analytics = useMemo(() => {
    if (!data) return null;
    return deriveAnalytics({
      payments: data.payments,
      ledgerEntries: data.ledgerEntries,
      reviewCases,
      reconciliationItems: data.reconciliationItems,
      retryAttempts: data.retryAttempts
    });
  }, [data, reviewCases]);

  const handleReviewAction = async (reviewCaseId: string, action: ReviewAction): Promise<void> => {
    if (processingReviewCaseIds.includes(reviewCaseId)) return;

    const targetCase = reviewCases.find((item) => item.id === reviewCaseId);
    if (!targetCase || targetCase.status !== "OPEN" || !loadMeta) return;
    if (loadMeta.reviewActionMode === "READ_ONLY") {
      setError("Manual review actions are unavailable until the live review queue API is online.");
      return;
    }

    const idempotencyKey = `review-${targetCase.id}-${action.toLowerCase()}`;
    if (reviewActions.some((event) => event.idempotencyKey === idempotencyKey)) return;

    const createdAt = new Date().toISOString();
    const fallbackStatus: ReviewCase["status"] =
      action === "APPROVE" ? "APPROVED" : action === "REJECT" ? "REJECTED" : targetCase.status;
    const fallbackOwner = action === "ESCALATE" ? "risk.lead@ledgerforge.local" : targetCase.assignedTo;
    const note =
      action === "APPROVE"
        ? "Case approved and removed from manual review queue."
        : action === "REJECT"
          ? "Case rejected and payment flagged for decline/follow-up."
          : "Case escalated to risk lead for secondary investigation.";
    const correlationId = `corr-${targetCase.id}-${Date.now().toString(36)}`;
    const actor = REVIEW_ACTOR_ID;

    setProcessingReviewCaseIds((current) => [...current, reviewCaseId]);

    try {
      let afterStatus = fallbackStatus;
      let nextOwner = fallbackOwner;
      let refreshedData: AppLoadResult | null = null;

      if (loadMeta.reviewActionMode === "API") {
        if (action === "ESCALATE") {
          throw new Error("Escalation is only available in mock fallback mode.");
        }
        const resolvedCase = await submitReviewDecision(reviewCaseId, action, note, correlationId, actor);
        afterStatus = resolvedCase.status;
        nextOwner = resolvedCase.assignedTo;

        try {
          refreshedData = await loadAppData();
        } catch (refreshError) {
          setError(
            refreshError instanceof Error
              ? `Review decision was submitted, but refreshing live payment state failed: ${refreshError.message}`
              : "Review decision was submitted, but refreshing live payment state failed."
          );
        }
      }

      const event: ReviewActionEvent = {
        id: `${targetCase.id}-${action.toLowerCase()}-${Date.now().toString(36)}`,
        reviewCaseId: targetCase.id,
        paymentId: targetCase.paymentId,
        action,
        beforeStatus: targetCase.status,
        afterStatus,
        actor,
        note,
        correlationId,
        idempotencyKey,
        createdAt
      };

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
        if (refreshedData) {
          applyLoadedState(refreshedData);
        } else {
          setData((current) => {
            if (!current) return current;
            return {
              ...current,
              payments: current.payments.map((payment) =>
                payment.id === targetCase.paymentId ? applyReviewAction(payment, action, createdAt, note) : payment
              )
            };
          });
        }
        setReviewActions((current) => [event, ...current]);
        if (refreshedData) {
          setError(null);
        }
      });
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : "Failed to apply review action.");
    } finally {
      setProcessingReviewCaseIds((current) => current.filter((id) => id !== reviewCaseId));
    }
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
      case "analytics":
        return <AnalyticsPage analytics={analytics} />;
      case "payments":
        return (
          <PaymentsPage
            payments={filteredPayments}
            selectedPayment={selectedPayment}
            selectedPaymentLedgerEntries={selectedPaymentLedgerEntries}
            reviewCases={reviewCases}
            reconciliationItems={data.reconciliationItems}
            auditEntries={data.auditEntries}
            retryAttempts={data.retryAttempts}
            reviewActions={reviewActions}
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
            payments={data.payments}
            reviewCases={reviewCases}
            reviewActions={reviewActions}
            reconciliationItems={data.reconciliationItems}
            retryAttempts={data.retryAttempts}
            repairRecommendations={data.repairRecommendations}
            processingReviewCaseIds={processingReviewCaseIds}
            reviewActionMode={loadMeta?.reviewActionMode ?? "READ_ONLY"}
            onReviewAction={handleReviewAction}
          />
        );
      default:
        return null;
    }
  }, [
    analytics,
    data,
    filteredPayments,
    processingReviewCaseIds,
    query,
    reviewActions,
    reviewCases,
    selectedPayment,
    selectedPaymentLedgerEntries,
    view,
    loadMeta
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
          <span className="pill">Source: {loadMeta?.sourceLabel ?? "Loading"}</span>
          <span className="pill">
            Review mode:{" "}
            {loadMeta?.reviewActionMode === "API"
              ? "Live decisions"
              : loadMeta?.reviewActionMode === "LOCAL"
                ? "Mock actions"
                : "Read only"}
          </span>
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
        {!loading && !error && loadMeta && loadMeta.warnings.length > 0 && <DataModeNotice meta={loadMeta} />}
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

function DataModeNotice({ meta }: { meta: AppLoadMeta }): JSX.Element {
  return (
    <section className="panel">
      <p className="eyebrow">Operating mode</p>
      <h2>{meta.sourceLabel}</h2>
      <ul className="list">
        {meta.warnings.map((warning) => (
          <li key={warning} className="list-item">
            <span>{warning}</span>
          </li>
        ))}
      </ul>
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

function AnalyticsPage({ analytics }: { analytics: AnalyticsData | null }): JSX.Element {
  if (!analytics) {
    return (
      <section className="panel">
        <p className="eyebrow">Analytics</p>
        <h2>Reporting is unavailable</h2>
        <p className="muted">Derived analytics could not be computed from the current dataset.</p>
      </section>
    );
  }

  const maxTrendTotal = Math.max(1, ...analytics.trends.map((bucket) => bucket.total));
  const maxBandCount = Math.max(1, ...analytics.riskBands.map((band) => band.count));

  return (
    <section className="dashboard analytics-page">
      <article className="panel animated">
        <p className="eyebrow">Operational reporting</p>
        <h2>Risk, settlement, and backlog signals</h2>
        <div className="metric-grid report-grid">
          <MetricCard label="Approval rate" value={asPct(analytics.headline.approvalRate)} />
          <MetricCard label="Reject rate" value={asPct(analytics.headline.rejectRate)} />
          <MetricCard label="Review rate" value={asPct(analytics.headline.reviewRate)} />
          <MetricCard
            label="Settlement coverage"
            value={asPct(analytics.headline.settlementCoverageRate)}
            tone={analytics.headline.executedWithoutLedgerCount === 0 ? "good" : "warn"}
          />
          <MetricCard label="Anomaly rate" value={asPct(analytics.headline.anomalyRate)} />
          <MetricCard
            label="Drifted journals"
            value={String(analytics.headline.driftedJournalCount)}
            tone={analytics.headline.driftedJournalCount === 0 ? "good" : "warn"}
          />
          <MetricCard
            label="Missing ledger"
            value={String(analytics.headline.executedWithoutLedgerCount)}
            tone={analytics.headline.executedWithoutLedgerCount === 0 ? "good" : "warn"}
          />
          <MetricCard label="Oldest backlog" value={formatDuration(analytics.headline.oldestBacklogMinutes)} />
        </div>
      </article>

      <div className="split-grid">
        <article className="panel animated stagger-1">
          <div className="panel-head">
            <div>
              <p className="eyebrow">Fraud trends</p>
              <h3>Recent payment decision flow</h3>
            </div>
            <span className="pill">Derived from payment timestamps</span>
          </div>
          <div className="stacked-list">
            {analytics.trends.map((bucket) => (
              <div key={bucket.label} className="stacked-row">
                <div className="stacked-meta">
                  <strong>{bucket.label}</strong>
                  <span>{bucket.total} payments</span>
                </div>
                <div className="stacked-track">
                  <span
                    className="stack-segment stack-approve"
                    style={{ width: `${(bucket.approved / maxTrendTotal) * 100}%` }}
                  />
                  <span
                    className="stack-segment stack-review"
                    style={{ width: `${(bucket.review / maxTrendTotal) * 100}%` }}
                  />
                  <span
                    className="stack-segment stack-reject"
                    style={{ width: `${(bucket.rejected / maxTrendTotal) * 100}%` }}
                  />
                  <span
                    className="stack-segment stack-anomaly"
                    style={{ width: `${(bucket.anomalies / maxTrendTotal) * 100}%` }}
                  />
                </div>
                <div className="stacked-breakdown">
                  <span>approve {bucket.approved}</span>
                  <span>review {bucket.review}</span>
                  <span>reject {bucket.rejected}</span>
                  <span>anomalies {bucket.anomalies}</span>
                </div>
              </div>
            ))}
          </div>
        </article>

        <article className="panel animated stagger-2">
          <div className="panel-head">
            <div>
              <p className="eyebrow">Risk bands</p>
              <h3>Score distribution and review pressure</h3>
            </div>
            <span className="pill">Risk engine summary</span>
          </div>
          <div className="band-grid">
            {analytics.riskBands.map((band) => (
              <div key={band.label} className="band-card">
                <div className="band-head">
                  <strong>{band.label}</strong>
                  <span>{band.range}</span>
                </div>
                <div className="band-bar">
                  <span style={{ width: `${(band.count / maxBandCount) * 100}%` }} />
                </div>
                <div className="band-stats">
                  <span>{band.count} payments</span>
                  <span>{asPct(band.share)} of flow</span>
                  <span>{band.openReviewCount} open reviews</span>
                  <span>{band.anomalyCount} anomalies</span>
                </div>
              </div>
            ))}
          </div>
        </article>
      </div>

      <div className="split-grid">
        <article className="panel animated">
          <p className="eyebrow">Settlement health</p>
          <h3>Ledger-backed execution coverage</h3>
          <div className="detail-grid report-detail-grid">
            <Detail label="Ledger required">{String(analytics.settlement.ledgerRequiredCount)}</Detail>
            <Detail label="Balanced">{String(analytics.settlement.balancedCount)}</Detail>
            <Detail label="Drifted">{String(analytics.settlement.driftedPaymentCount)}</Detail>
            <Detail label="Missing ledger">{String(analytics.settlement.missingLedgerCount)}</Detail>
            <Detail label="Settled">{String(analytics.settlement.settledCount)}</Detail>
            <Detail label="Captured">{String(analytics.settlement.capturedCount)}</Detail>
            <Detail label="Reserved">{String(analytics.settlement.reservedCount)}</Detail>
            <Detail label="Refund or dispute">
              {String(analytics.settlement.refundedCount + analytics.settlement.chargebackCount)}
            </Detail>
          </div>

          <div className="divider" />

          <p className="eyebrow">Status performance</p>
          <h3>Per-state operating report</h3>
          <div className="scroll-area scroll-area-sm">
            <table className="data-table data-table-tight">
              <thead>
                <tr>
                  <th>Status</th>
                  <th>Count</th>
                  <th>Avg risk</th>
                  <th>Avg latency</th>
                  <th>Open reviews</th>
                  <th>Ledger coverage</th>
                </tr>
              </thead>
              <tbody>
                {analytics.statusRows.map((row) => (
                  <tr key={row.status}>
                    <td>{row.status}</td>
                    <td>{row.count}</td>
                    <td>{Math.round(row.averageRiskScore)}</td>
                    <td>{formatLatency(row.averageLatencyMs)}</td>
                    <td>{row.openReviewCount}</td>
                    <td>{row.ledgerCoverageRate === null ? "n/a" : asPct(row.ledgerCoverageRate)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </article>

        <article className="panel animated stagger-1">
          <p className="eyebrow">Operational latency</p>
          <h3>Backlog and anomaly pressure</h3>
          <div className="detail-grid report-detail-grid">
            <Detail label="Average latency">{formatLatency(analytics.latency.averageMs)}</Detail>
            <Detail label="P50 latency">{formatLatency(analytics.latency.p50Ms)}</Detail>
            <Detail label="P95 latency">{formatLatency(analytics.latency.p95Ms)}</Detail>
            <Detail label="Max latency">{formatLatency(analytics.latency.maxMs)}</Detail>
            <Detail label="Longest payment">{analytics.latency.longestPaymentId ?? "n/a"}</Detail>
            <Detail label="Longest status">{analytics.latency.longestStatus ?? "n/a"}</Detail>
          </div>

          <div className="divider" />

          <p className="eyebrow">Anomaly mix</p>
          <h3>Reconciliation rollup</h3>
          <div className="rollup-grid">
            <div className="rollup-card">
              <span>By category</span>
              <ul className="list compact-list">
                {analytics.anomaliesByCategory.map((item) => (
                  <li key={item.label} className="list-item compact-list-item">
                    <strong>{item.label}</strong>
                    <span>{item.count}</span>
                  </li>
                ))}
              </ul>
            </div>
            <div className="rollup-card">
              <span>By severity</span>
              <ul className="list compact-list">
                {analytics.anomaliesBySeverity.map((item) => (
                  <li key={item.label} className="list-item compact-list-item">
                    <strong>{item.label}</strong>
                    <span>{item.count}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>

          <div className="divider" />

          <p className="eyebrow">Review backlog</p>
          <h3>Queue age and ownership</h3>
          {analytics.backlog.length === 0 ? (
            <p className="muted">No open review cases are waiting in the queue.</p>
          ) : (
            <ul className="list">
              {analytics.backlog.map((item) => (
                <li key={item.reviewCaseId} className="list-item">
                  <div>
                    <strong>
                      {item.paymentId} · {item.paymentStatus}
                    </strong>
                    <p>{item.reason}</p>
                    <p>
                      risk {item.riskScore} · anomalies {item.anomalyCount} · retries {item.retryCount}
                    </p>
                  </div>
                  <div className="list-meta">
                    <span>{item.owner}</span>
                    <span>{formatDuration(item.ageMinutes)}</span>
                  </div>
                </li>
              ))}
            </ul>
          )}

          {analytics.ownerWorkloads.length > 0 && (
            <>
              <div className="divider" />
              <p className="eyebrow">Owner load</p>
              <h3>Who is holding the queue</h3>
              <ul className="list compact-list">
                {analytics.ownerWorkloads.map((item) => (
                  <li key={item.owner} className="list-item compact-list-item">
                    <div>
                      <strong>{item.owner}</strong>
                      <p>{item.openCases} open cases</p>
                    </div>
                    <div className="list-meta">
                      <span>avg age {formatDuration(item.averageAgeMinutes)}</span>
                      <span>{item.highSeveritySignals} high severity</span>
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
        </article>
      </div>
    </section>
  );
}

function PaymentsPage({
  payments,
  selectedPayment,
  selectedPaymentLedgerEntries,
  reviewCases,
  reconciliationItems,
  auditEntries,
  retryAttempts,
  reviewActions,
  query,
  onQueryChange,
  onSelectPayment
}: {
  payments: Payment[];
  selectedPayment: Payment | null;
  selectedPaymentLedgerEntries: LedgerEntry[];
  reviewCases: ReviewCase[];
  reconciliationItems: ReconciliationItem[];
  auditEntries: AuditEntry[];
  retryAttempts: RetryAttempt[];
  reviewActions: ReviewActionEvent[];
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
  const selectedReviewCase = useMemo(() => {
    if (!selectedPayment) return null;
    return reviewCases.find((reviewCase) => reviewCase.paymentId === selectedPayment.id) ?? null;
  }, [reviewCases, selectedPayment]);

  const selectedAnomalies = useMemo(() => {
    if (!selectedPayment) return [];
    return reconciliationItems.filter((item) => item.paymentId === selectedPayment.id);
  }, [reconciliationItems, selectedPayment]);

  const selectedRetryHistory = useMemo(() => {
    if (!selectedPayment) return [];
    const rootAttempt = retryAttempts.find((attempt) => attempt.paymentId === selectedPayment.id);
    if (!rootAttempt) return [];
    return retryAttempts
      .filter((attempt) => attempt.fingerprint === rootAttempt.fingerprint)
      .sort((left, right) => left.attemptNumber - right.attemptNumber || left.createdAt.localeCompare(right.createdAt));
  }, [retryAttempts, selectedPayment]);

  const selectedAuditEntries = useMemo(() => {
    if (!selectedPayment) return [];
    const sessionEntries: AuditEntry[] = reviewActions
      .filter((event) => event.paymentId === selectedPayment.id)
      .map((event) => ({
        id: `session-${event.id}`,
        paymentId: event.paymentId,
        timestamp: event.createdAt,
        actor: event.actor,
        source: "SESSION",
        title: `Review ${event.action.toLowerCase()}`,
        detail: `${event.note} (${event.beforeStatus} -> ${event.afterStatus})`
      }));

    return [...auditEntries.filter((entry) => entry.paymentId === selectedPayment.id), ...sessionEntries].sort(
      (left, right) => right.timestamp.localeCompare(left.timestamp)
    );
  }, [auditEntries, reviewActions, selectedPayment]);

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
            onChange={(event: ChangeEvent<HTMLInputElement>) => onQueryChange(event.target.value)}
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

            <div className="insight-grid">
              <Detail label="Decision">{selectedPayment.decision}</Detail>
              <Detail label="Review queue">
                {selectedReviewCase ? `${selectedReviewCase.status} · ${selectedReviewCase.assignedTo}` : "No linked review"}
              </Detail>
              <Detail label="Recon signals">
                {selectedAnomalies.length === 0 ? "Clear" : `${selectedAnomalies.length} active`}
              </Detail>
              <Detail label="Retry cluster">
                {selectedRetryHistory.length <= 1 ? "Single attempt" : `${selectedRetryHistory.length} attempts`}
              </Detail>
            </div>

            <h4>Execution timeline</h4>
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

            <div className="divider" />

            <h4>Retry history</h4>
            {selectedRetryHistory.length <= 1 ? (
              <p className="muted">No related retry cluster was detected for this payment fingerprint.</p>
            ) : (
              <ol className="timeline timeline-compact">
                {selectedRetryHistory.map((attempt) => (
                  <li key={attempt.id}>
                    <div>
                      <strong>
                        Attempt {attempt.attemptNumber} · {attempt.paymentId}
                      </strong>
                      <p>{attempt.detail}</p>
                      <p>
                        decision: {attempt.decision} · status: {attempt.status} · outcome: {attempt.outcome}
                      </p>
                    </div>
                    <span>{asDate(attempt.createdAt)}</span>
                  </li>
                ))}
              </ol>
            )}

            <div className="divider" />

            <h4>Audit trail</h4>
            {selectedAuditEntries.length === 0 ? (
              <p className="muted">No audit rows are available for this payment.</p>
            ) : (
              <ol className="timeline timeline-compact">
                {selectedAuditEntries.map((entry) => (
                  <li key={entry.id}>
                    <div>
                      <strong>{entry.title}</strong>
                      <p>{entry.detail}</p>
                      <p>
                        actor: {entry.actor} · source: {entry.source}
                      </p>
                    </div>
                    <span>{asDate(entry.timestamp)}</span>
                  </li>
                ))}
              </ol>
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
              onChange={(event: ChangeEvent<HTMLInputElement>) => setAccountFilter(event.target.value)}
            />
            <select
              value={directionFilter}
              onChange={(event: ChangeEvent<HTMLSelectElement>) =>
                setDirectionFilter(event.target.value as typeof directionFilter)
              }
            >
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
  payments,
  reviewCases,
  reviewActions,
  reconciliationItems,
  retryAttempts,
  repairRecommendations,
  processingReviewCaseIds,
  reviewActionMode,
  onReviewAction
}: {
  payments: Payment[];
  reviewCases: ReviewCase[];
  reviewActions: ReviewActionEvent[];
  reconciliationItems: ReconciliationItem[];
  retryAttempts: RetryAttempt[];
  repairRecommendations: RepairRecommendation[];
  processingReviewCaseIds: string[];
  reviewActionMode: AppLoadMeta["reviewActionMode"];
  onReviewAction: (reviewCaseId: string, action: ReviewAction) => Promise<void> | void;
}): JSX.Element {
  const recentActions = reviewActions.slice(0, 8);
  const retriesByPayment = useMemo(() => {
    const counts = new Map<string, number>();
    for (const attempt of retryAttempts) {
      counts.set(attempt.paymentId, (counts.get(attempt.paymentId) ?? 0) + 1);
    }
    return counts;
  }, [retryAttempts]);
  const reconByPayment = useMemo(() => {
    const counts = new Map<string, number>();
    for (const item of reconciliationItems) {
      counts.set(item.paymentId, (counts.get(item.paymentId) ?? 0) + 1);
    }
    return counts;
  }, [reconciliationItems]);
  const paymentsById = useMemo(() => new Map(payments.map((payment) => [payment.id, payment])), [payments]);

  return (
    <section className="split-grid">
      <article className="panel animated">
        <p className="eyebrow">Fraud operations</p>
        <h2>Manual review console</h2>
        <div className="scroll-area">
          <table className="data-table">
            <thead>
              <tr>
                <th>Case</th>
                <th>Payment</th>
                <th>Status</th>
                <th>Reason</th>
                <th>Retries</th>
                <th>Recon</th>
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
                    <td>{retriesByPayment.get(item.paymentId) ?? 0}</td>
                    <td>{reconByPayment.get(item.paymentId) ?? 0}</td>
                    <td>{item.assignedTo}</td>
                    <td>
                      {isOpen && reviewActionMode !== "READ_ONLY" ? (
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
                          {reviewActionMode === "LOCAL" && (
                            <button
                              className="table-action table-action-escalate"
                              disabled={isBusy}
                              onClick={() => onReviewAction(item.id, "ESCALATE")}
                            >
                              Escalate
                            </button>
                          )}
                        </div>
                      ) : isOpen ? (
                        <span className="muted">Read only</span>
                      ) : (
                        <span className="muted">Closed</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
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
                <span>{paymentsById.get(item.paymentId)?.status ?? "UNKNOWN"}</span>
                <span>{retriesByPayment.get(item.paymentId) ?? 0} retries</span>
                <span>{asDate(item.createdAt)}</span>
              </div>
            </li>
          ))}
        </ul>

        <div className="divider" />

        <p className="eyebrow">Repair playbook</p>
        <h3>Recommended next actions</h3>
        <ul className="list">
          {repairRecommendations.map((item) => (
            <li key={item.id} className="list-item">
              <div>
                <strong>
                  {item.title} · {item.paymentId}
                </strong>
                <p>{item.detail}</p>
                <p>{item.guardrail}</p>
              </div>
              <div className="list-meta">
                <span className={cx("tag", severityTone(item.urgency))}>{item.urgency}</span>
                <span>{item.owner}</span>
                <span>{paymentsById.get(item.paymentId)?.status ?? "UNKNOWN"}</span>
              </div>
            </li>
          ))}
        </ul>

        <div className="divider" />

        <p className="eyebrow">Review audit</p>
        <h3>Session decision trail</h3>
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

function Detail({ label, children }: { label: string; children: ReactNode }): JSX.Element {
  return (
    <div className="detail">
      <span>{label}</span>
      <strong>{children}</strong>
    </div>
  );
}

function statusTone(status: string): string {
  if (["SETTLED", "CAPTURED", "APPROVED", "RESERVED", "REFUNDED"].includes(status)) return "status-good";
  if (["REJECTED", "REVERSED", "CHARGEBACK", "CANCELLED"].includes(status)) return "status-bad";
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

function applyReviewAction(payment: Payment, action: ReviewAction, createdAt: string, note: string): Payment {
  if (action === "ESCALATE") {
    return payment;
  }

  const status = action === "APPROVE" ? "RESERVED" : "REJECTED";
  return {
    ...payment,
    status,
    updatedAt: createdAt,
    reasonCodes:
      action === "REJECT" && !payment.reasonCodes.includes("manual_review_rejected")
        ? [...payment.reasonCodes, "manual_review_rejected"]
        : payment.reasonCodes,
    events: [
      ...payment.events,
      {
        id: `${payment.id}-manual-review-${action.toLowerCase()}`,
        type: `manual_review_${action.toLowerCase()}`,
        status,
        timestamp: createdAt,
        actor: "operator.ui@ledgerforge.local",
        note
      }
    ]
  };
}

function formatDuration(totalMinutes: number): string {
  if (totalMinutes <= 0) return "0m";
  if (totalMinutes < 60) return `${totalMinutes}m`;

  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
}

function formatLatency(valueMs: number): string {
  if (valueMs < 1_000) return `${valueMs} ms`;
  if (valueMs < 60_000) return `${(valueMs / 1_000).toFixed(1)} s`;
  return `${(valueMs / 60_000).toFixed(1)} min`;
}

export default App;
