# AntFlow

Ant Design Pro + wflow fusion. Visual form designer + approval workflow.

## Status (2026-07-15)

- ✅ Backend (Spring Boot 3 + Java 17 + MyBatis-Plus + JWT + Flyway) — compiles, **16 unit tests green**.
- ✅ PostgreSQL 17 schema (V1+V3 migrations) — ready to apply.
- ✅ Custom approval engine — `ProcessEngine.start/approve/reject/withdraw`, OR-sign short-circuit, `@Version` optimistic locking, linear-flow validator, end-node gating.
- ✅ Form designer storage — `FormDefinition` with JSONB schema, draft/publish flow, schema validation.
- ✅ Form runtime — `FormData` with `form_def_version` snapshot.
- ✅ Org module — Company / Department (ltree) / User / Role + ltree subtree + JWT auth + login rate limit.
- 🚧 Frontend pages (designer / runtime / task center) — scaffolded; React Flow + zustand + ProTable to be wired.
- ⏸ Live integration tests — pending Docker daemon availability.

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
npm install    # one-off
npm start      # UMI_ENV=dev, dev server on http://localhost:8000
# Proxy: /api/* → http://localhost:8080 via config/proxy.ts
```

Open `http://localhost:8000`, log in with **admin / ant.design** (or **bob / ant.design**). Both users are seeded by V2 migration.

## Layout

```
antflow/
├── backend/                # Spring Boot 3 + Java 17 (see backend/pom.xml)
├── frontend/               # Umi Max 4 + React 18 + TS — port 8000
├── infra/docker-compose.yml  # postgres:17-alpine
├── docs/superpowers/
│   ├── specs/2026-07-15-antflow-fusion-design.md      # Design spec
│   └── plans/2026-07-15-antflow-fusion-impl.md       # Implementation plan
└── README.md
```

## Tests

```bash
cd backend && mvn test
# Currently green: JwtService (3), FormDefinitionService schema (4), AssigneeResolver (5), ProcessDefinitionService validation (4) = 16 tests.
```

## What's next

Frontend pages are scaffolded; the missing pieces are:

1. `frontend/src/registry/formRegistry.ts` + `frontend/src/components/form-fields/*`
2. `frontend/src/components/FormRenderer/FormRenderer.tsx`
3. `frontend/src/pages/designer/form/*` (palette, canvas, inspector, zustand store)
4. `frontend/src/pages/designer/process/*` (React Flow, AssigneePicker)
5. `frontend/src/pages/runtime/form/*` (Fill, List)
6. `frontend/src/pages/tasks/*` + `frontend/src/pages/proc/*`
7. CI workflow at `.github/workflows/ci.yml`
8. Playwright E2E test at `frontend/e2e/full-flow.spec.ts`

These follow the plan verbatim (`docs/superpowers/plans/...`). Run that plan's tasks task-by-task when ready.
