# Frontend: LedgerForge Operator Console

React + TypeScript operator dashboard for the LedgerForge payments platform.

## Features

- Mission control dashboard with payment throughput, risk and latency KPIs
- Payment explorer with search, risk reasons, and status timeline
- Ledger explorer with journal filtering and projected account balances
- Fraud/reconciliation console with review queue, anomaly list, and operator review actions
- Review action audit trail with correlation/idempotency visibility
- Thin API client with mock fallback data for local development

## Run locally

```bash
cd frontend
npm install
npm run dev
```

The app runs on `http://127.0.0.1:5173` by default.

## Build

```bash
cd frontend
npm run build
npm run preview
```

## API configuration

Set `VITE_API_BASE_URL` to point to your backend (default: `http://localhost:8080`).

Example:

```bash
VITE_API_BASE_URL=http://localhost:8080 npm run dev
```

Expected endpoints (read-only in this UI):

- `GET /api/metrics`
- `GET /api/payments`
- `GET /api/payments/{id}/ledger`
- `GET /api/fraud/reviews`
- `GET /api/reconciliation/reports`

If these endpoints are unavailable, the app automatically falls back to in-repo sample data.
