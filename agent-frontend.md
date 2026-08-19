# Frontend Agent Context & Guidelines

## Technology Stack

- **Framework:** React / Next.js or Vite (TypeScript)
- **Styling:** Tailwind CSS + shadcn/ui
- **State & Real-Time:** TanStack Query + WebSocket / Server-Sent Events (SSE)
- **Icons & Visuals:** Lucide React, Recharts / Lightweight Charts (TradingView)

## Core Architecture Principles

1. **Real-Time Responsiveness:** Prioritize low-latency UI updates for live P&L, position tables, and order books.
2. **Safety & Confirmation:** Critical actions (e.g., L1/L2/L3 Kill Switches, Panic Square-Off, Order Cancellation) must require double confirmation modals to prevent accidental execution.
3. **Resilience:** Gracefully handle WebSocket reconnects with visual indicators for broker connection health (Zerodha Kite & Shoonya statuses).

## Key UI Components

1. **Control Plane / Kill Switch Header:**
   - Broker health badges (Kite / Shoonya / Engine Status).
   - Global Panic button + account-level freeze triggers.
2. **Live Position Table:**
   - Partitioned tabs: **Intraday (MIS)** vs **Positional (NRML)**.
   - Live MTM P&L, realized/unrealized breakdown, strike info, quick square-off action.
3. **Live Order Book:**
   - Filterable by Broker, Strategy, and Status (`OPEN`, `FILLED`, `REJECTED`, `CANCELLED`).
4. **Strategy Monitor:**
   - Active strategies, assigned accounts, current state, and pause/resume toggles.
