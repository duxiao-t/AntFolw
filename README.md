# AntFlow

Ant Design Pro + wflow fusion. Visual form designer + approval workflow + enterprise mobile client.

## Status (2026-07-31)

- ✅ Backend (Spring Boot 3 + Java 17 + MyBatis-Plus + JWT + Flyway V1–V21) — `mvn test` green.
- ✅ PostgreSQL 17 schema (Flyway V1–V21) + MinIO object storage for mobile attachments.
- ✅ Custom approval engine — tree process, OR/AND, SELF_SELECT, withdraw, optimistic locking, parallel gateway (`PARALLEL`), delay/trigger automation, reject-to-previous + rework-on-original-instance.
- ✅ Formal numbers — `t_user.employee_no` (6-digit) + `t_form_data.business_no` (12-digit); desktop contacts maintain/import/export employee numbers.
- ✅ RBAC & audit — action/page permissions (`t_permission`, `t_role_permission`), form resource grants (`t_form_resource_grant`), append-only audit events and audit archive (`/api/audit/*`).
- ✅ Node-level field permissions — per-approval-node `HIDDEN`/`READONLY`/`EDITABLE` (default readonly), with editable values validated and written back into form data on approve.
- ✅ Automation — persistent `t_workflow_job` for DELAY/TRIGGER nodes with scheduler, webhook SSRF guard, retry/recovery.
- ✅ Form designer storage + runtime snapshot (`form_def_version`).
- ✅ Org module — Company / Department (ltree) / User / Role + JWT + login rate limit.
- ✅ Desktop frontend — 14-field registry + FormRenderer + process designer + contacts + approval forms/records + security roles/audit + report center + system settings.
- ✅ **Mobile client (`mobile/`)** — independent Vite app at `/mobile/`: workbench, dynamic form fill/draft, self-select, submit, task approve/reject, process detail, rework resubmit, offline recovery, branding fallback, enterprise gates (bundle/perf/a11y/e2e).
- ✅ CI — backend `mvn test` + frontend lint/tsc/build + mobile enterprise checks.
- 📄 Mobile acceptance evidence — `docs/mobile-enterprise-verification.md`; handover — `docs/session-summary-2026-07-31.md`.
- ⏸ Full live integration depends on local PostgreSQL, local MinIO, and backend started from this branch.
- ✅ Workspace changes committed; see git log for the backend/desktop/mobile split.

## Prerequisites

- Node.js >= 22
- Java 17
- Maven 3.9
- PostgreSQL 17 running locally on `localhost:5432`
- MinIO Server running locally on `localhost:9000` with console on `localhost:9001`

## Quick start

### 1. Start local database and object storage

Create or reuse a local PostgreSQL database named `antflow`. The default backend
credentials are `postgres / Tao@1234`; override them with Spring datasource
environment variables if your local database uses different credentials.

Start a local MinIO server outside this repository:

```powershell
$env:MINIO_ROOT_USER='minioadmin'
$env:MINIO_ROOT_PASSWORD='minioadmin'
minio.exe server E:\minio-data --address ':9000' --console-address ':9001'
```

MinIO is exposed at `http://localhost:9000`; the console is at `http://localhost:9001`.
The backend defaults to MinIO storage. Mobile attachments are not written to a
project-local file directory.

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
├── backend/                 # Spring Boot 3 + Java 17 + Flyway V1–V21
├── frontend/                # Umi Max desktop — port 8000
├── mobile/                  # Vite mobile SPA — base /mobile/
├── infra/                   # nginx example
├── _preview/                # mobile UI design reference (static)
├── docs/
│   ├── mobile-enterprise-verification.md
│   ├── session-summary-2026-07-31.md
│   └── superpowers/         # specs + implementation plans
├── agent.md                 # mobile UI task context (historical)
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

当前基线：移动端 `check:enterprise` 为 183 个 Vitest 测试 + bundle ≤ 250 KiB；`test:e2e` 为 Playwright 四视口 + `rework-original-form` 原单重提用例。


### Desktop E2E (optional live)

Pre-req: local PostgreSQL, local MinIO, backend on :8080.

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

- Sequential multi-counter-sign / task-level timeout (remaining engine phase-two items)
- Dynamic form permissions per node
- Mobile form/process designers; mobile org administration
- Enterprise WeChat silent login / JS-SDK / app messages (adapter only)
- Full theme editor; arbitrary CSS from server
- Multi-tenancy guard, OAuth2/OIDC, Redis caching, i18n
- Print
- Core flows that require PWA install or Service Worker
