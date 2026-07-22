# AntFlow

Ant Design Pro + wflow fusion. Visual form designer + approval workflow + enterprise mobile client.

## Status (2026-07-22)

- ✅ Backend (Spring Boot 3 + Java 17 + MyBatis-Plus + JWT + Flyway) — unit tests green (**88** tests, 1 skipped).
- ✅ PostgreSQL 17 schema (Flyway V1+) — ready to apply.
- ✅ Custom approval engine — tree process, OR/AND, SELF_SELECT, withdraw, optimistic locking.
- ✅ Form designer storage + runtime snapshot (`form_def_version`).
- ✅ Org module — Company / Department (ltree) / User / Role + JWT + login rate limit.
- ✅ Desktop frontend — 14-field registry + FormRenderer + process designer + admin/task pages.
- ✅ **Mobile client (`mobile/`)** — independent Vite app at `/mobile/`: workbench, dynamic form fill/draft, self-select, submit, task approve/reject, process detail, offline recovery, branding fallback, enterprise gates (bundle/perf/a11y/e2e).
- ✅ CI — backend `mvn test` + frontend lint/tsc/build + mobile enterprise checks.
- 📄 Mobile acceptance evidence — `docs/mobile-enterprise-verification.md`.
- ⏸ Full live integration depends on Docker + backend started from this branch.

## Prerequisites

- Node.js >= 22
- Java 17
- Maven 3.9
- Docker Desktop (daemon running)

## Quick start

### 1. Start the database

```bash
cd infra
docker compose up -d
docker exec antflow-postgres psql -U antflow -d antflow -c "SELECT extname FROM pg_extension WHERE extname IN ('ltree','pgcrypto');"
# Expected: 2 rows (ltree, pgcrypto)
```

### 2. Start the backend

```bash
cd backend
mvn -B spring-boot:run
# First boot applies Flyway migrations (seed: admin/bob, password ant.design).
```

Visit `http://localhost:8080/actuator/health` (or `PORT=8081` for mobile-oriented local docs).

Smoke auth:

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ant.design"}'
```

### 3. Start the desktop frontend

```bash
cd frontend
npm install --no-audit --no-fund
npm start                            # http://localhost:8000
# Proxy: /api/* → backend via config/proxy.ts
```

Log in with **admin / ant.design** or **bob / ant.design**.

### 4. Start the mobile client

```bash
cd mobile
npm ci --no-audit --no-fund
npm run dev                          # http://localhost:5173/mobile/login
# Vite base is fixed to /mobile/; API still /api/
```

Quality gates:

```bash
cd mobile
npm run check:enterprise             # lint + unit + build + bundle budget
npm run test:e2e                     # Playwright, 4 viewports
```

## Layout

```text
antflow/
├── backend/                 # Spring Boot 3 + Java 17
├── frontend/                # Umi Max desktop — port 8000
├── mobile/                  # Vite mobile SPA — base /mobile/
├── infra/                   # docker-compose, nginx example
├── docs/
│   ├── mobile-enterprise-verification.md
│   └── superpowers/         # specs + implementation plans
├── codex.md                 # agent quick reference (incl. mobile)
├── CLAUDE.md
└── README.md
```

## Architecture (mobile)

- **Shell**: bottom tabs — Workbench / Tasks / Profile.
- **Forms**: 14 field types, drafts (user-scoped recovery), SELF_SELECT, confirm, idempotent start.
- **Tasks**: pending / started / done; approve / reject / withdraw with server rules.
- **Brand**: published tokens → CSS variables; safe fallback when public branding unavailable.
- **Platform**: `BrowserAdapter` now; WeCom adapter boundary only (phase two: silent login / JS-SDK / app messages).
- **Non-goals (phase one)**: mobile designers, org admin on phone, full theme editor, PWA-required core flows.

## Tests

### Backend

```bash
cd backend && mvn test
```

### Desktop frontend

```bash
cd frontend
npm run biome:lint
npm test
npm run tsc
npm run build
```

### Mobile

```bash
cd mobile
npm run check:enterprise
npm run test:e2e
```

### Desktop E2E (optional live)

Pre-req: docker compose up, backend on :8080.

```bash
cd frontend
npx playwright install chromium --with-deps
npx playwright test
```

## CI

`.github/workflows/ci.yml` on push/PR:

- **backend**: `mvn test` (Java 17)
- **frontend**: install, Biome lint, tsc, build (Node 22)
- **mobile**: enterprise check (and related gates when configured)

## Agent notes

- See `codex.md` and `CLAUDE.md` for domain rules and module commands.
- Mobile detailed runbook: `mobile/README.md`.
- Acceptance evidence: `docs/mobile-enterprise-verification.md`.

## Out of scope (MVP / mobile phase-one non-goals)

Per fusion + mobile enterprise specs:

- Parallel branches / multi-counter-sign / reject-back (engine phase-two items)
- Dynamic form permissions per node
- Mobile form/process designers; mobile org administration
- Enterprise WeChat silent login / JS-SDK / app messages (adapter only)
- Full theme editor; arbitrary CSS from server
- Multi-tenancy guard, OAuth2/OIDC, Redis caching, i18n
- Print/export/dashboards
- Core flows that require PWA install or Service Worker
