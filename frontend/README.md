# Frontend: LedgerForge Operator Console

React + TypeScript operator dashboard for the LedgerForge payments platform.

## Features

- Mission control dashboard with payment throughput, risk and latency KPIs
- Dedicated analytics workspace for fraud trends, risk-band mix, settlement coverage, reconciliation rollups, and backlog aging
- Payment explorer with search, synthesized execution timeline, per-payment ledger legs, retry history, and audit trail views
- Ledger explorer with journal filtering and projected account balances
- Fraud/reconciliation console with review queue, anomaly list, retry/recon counts, and operator review actions
- Repair playbook cards that map reconciliation anomalies to append-only investigation steps and owning teams
- Session review-action trail with correlation/idempotency visibility
- Hybrid data mode that prefers live payment/ledger APIs and only derives metrics/reconciliation when admin endpoints are absent
- Full mock fallback only when the core payment API is unavailable

## Run locally

```bash
cd frontend
npm ci
npm run dev
```

The app runs on `http://127.0.0.1:5173` by default.
If your environment needs an internal npm mirror, override `npm_config_registry` for the install command without changing the committed lockfile.

## Build

```bash
cd frontend
npm ci
npm run build
npm run preview
```

## API configuration

Set `VITE_API_BASE_URL` to point to your backend (default: `http://localhost:8080`).
If your backend requires operator authentication, set `VITE_API_BEARER_TOKEN` to a valid bearer token.

Example:

```bash
VITE_API_BASE_URL=http://localhost:8080 \
VITE_API_BEARER_TOKEN="<operator-bearer-token>" \
npm run dev
```

If the backend returns `401` or `403`, the UI surfaces that authorization failure instead of silently dropping into mock mode.

Expected read endpoints:

- `GET /api/metrics`
- `GET /api/payments`
- `GET /api/payments/{id}/ledger`
- `GET /api/fraud/reviews`
- `GET /api/reconciliation/reports`

Optional write endpoint for live review actions:

- `POST /api/fraud/reviews/{id}/decision`

Behavior by API availability:

- If `GET /api/payments` is unavailable, the app falls back to in-repo sample data.
- In full mock fallback, approve/reject/escalate controls stay available locally so demo review flows still produce session audit entries.
- If payment APIs are available but `metrics` or `reconciliation` endpoints are not, the UI keeps live payments and ledger data and derives those panels client-side.
- If the review queue API is unavailable, the fraud console stays read-only instead of mixing mock cases with live ledger data.
- In live API mode, approve/reject review actions re-fetch payment and ledger state after the decision so the console reflects the backend-posted reserve journal and resulting status directly.
- Retry clusters, audit rows, repair recommendations, and the analytics/reporting surface are synthesized from payment, ledger, review, and reconciliation data so operators can still investigate from source-of-truth APIs even when dedicated admin endpoints are sparse.
