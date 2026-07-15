# AntFlow

Ant Design Pro + wflow fusion. Visual form designer + approval workflow.

## Status (2026-07-16)

- ✅ Backend (Spring Boot 3 + Java 17 + MyBatis-Plus + JWT + Flyway) — compiles, **16 unit tests green**.
- ✅ PostgreSQL 17 schema (V1 schema + V2 bcrypt seed + V3 indexes) — ready to apply.
- ✅ Custom approval engine — `ProcessEngine.start/approve/reject/withdraw`, OR-sign short-circuit, `@Version` optimistic locking, linear-flow validator, end-node gating, NoAssigneeFoundException.
- ✅ Form designer storage — `FormDefinition` with JSONB + JacksonTypeHandler, draft/publish flow, schema validation.
- ✅ Form runtime — `FormData` with `form_def_version` snapshot.
- ✅ Org module — Company / Department (ltree) / User / Role + ltree subtree + JWT auth + 5-req/min login rate limit.
- ✅ Frontend — 14-field registry + recursive FormRenderer + zustand designer + React Flow process designer + ProTable admin pages + Inbox/Done/Sent/Detail task pages.
- ✅ CI — `.github/workflows/ci.yml` (backend `mvn test` + frontend lint/tsc/build).
- ✅ E2E — `frontend/e2e/full-flow.spec.ts` Playwright happy-path.
- ⏸ Live integration — pending your Docker daemon + first boot.

## Prerequisites

- Node.js >= 22 (you have 24)
- Java 17 (you have 17.0.12)
- Maven 3.9 (you have 3.9.14)
- Docker Desktop (with daemon running)

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
# First boot applies V1 (schema), V2 (seed: admin/bob with password 'ant.design'), V3 (indexes).
```

Visit `http://localhost:8080/actuator/health` to confirm it's up.

Smoke the auth flow:

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ant.design"}'
# Returns {"accessToken":"eyJ...","user":{...}}

TOKEN=...
curl -s http://localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
# Returns user profile with roles: ["admin","user"]
```

### 3. Start the frontend

```bash
cd frontend
npm install --no-audit --no-fund    # one-off, may take a few minutes
npm start                            # UMI_ENV=dev, dev server on http://localhost:8000
# Proxy: /api/* → http://localhost:8080 via config/proxy.ts
```

Open `http://localhost:8000`, log in with **admin / ant.design** (or **bob / ant.design**). Both users are seeded by V2 migration.

## Layout

```
antflow/
├── backend/                # Spring Boot 3 + Java 17 (see backend/pom.xml)
├── frontend/               # Umi Max 4 + React 18 + TS — port 8000
├── infra/docker-compose.yml  # postgres:17-alpine
├── .github/workflows/ci.yml  # backend mvn test + frontend lint/tsc/build
├── docs/superpowers/
│   ├── specs/2026-07-15-antflow-fusion-design.md      # Design spec
│   └── plans/2026-07-15-antflow-fusion-impl.md       # Implementation plan
└── README.md
```

## Tests

### Backend unit tests (no PG needed)

```bash
cd backend && mvn test
# Currently green: JwtService (3), FormDefinitionService schema (4),
# AssigneeResolver (5), ProcessDefinitionService validation (4) = 16 tests.
```

### Frontend E2E (Playwright happy-path)

Pre-req: docker compose up -d, backend running on :8080.

```bash
cd frontend
npx playwright install chromium --with-deps
npx playwright test
# Walks: admin login → design form → publish → wire 1-level approval (via API to skip
# React Flow drag) → bob submits → admin approves → instance APPROVED.
```

## CI

`.github/workflows/ci.yml` runs on push/PR to `master`/`main`:

- **backend**: `mvn test` on Java 17.
- **frontend**: install, Biome lint, tsc, build on Node 22.

## Frontend dependency setup note

On Windows, `node_modules` file locks can leave broken state if `npm install` is interrupted. Recover:

```bash
cd frontend
rm -rf node_modules package-lock.json
npm install --no-audit --no-fund
```

## Out of scope (MVP non-goals)

Per spec, explicitly NOT in MVP — reserved for v1.x:

- Parallel branches / multi-counter-sign / reject-back
- Dynamic form permissions per node
- Expression-based condition nodes
- Process versioning migrations
- File upload to S3/MinIO
- Multi-tenancy guard
- OAuth2/OIDC
- Redis caching
- i18n
- Print/export/dashboards