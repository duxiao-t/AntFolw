# AntFlow — Fusion Design Spec

- **Date**: 2026-07-15
- **Status**: Iterating on user feedback — not yet final
- **Driver**: project sponsor (user), engineering hand-off pending
- **Source dialog**: brainstorming session 2026-07-15 + 修改意见.txt feedback

## Purpose

Fuse the Ant Design Pro enterprise admin boilerplate with wflow's visual workflow + approval designer into a single, self-hostable application. The system lets non-technical admins design forms and approval flows, and lets end-users fill those forms and route them through approvers, with full audit trail.

This replaces none of the upstream projects wholesale — it is a **greenfield implementation** that:
- Reuses ant-design-pro's React/TS shell, layout, auth chrome
- Rewrites wflow's Vue 2 designer in React/TS (same UX paradigm)
- Reimplements wflow's Spring Boot 2 / Java 8 backend on Spring Boot 3 / Java 17 / PostgreSQL 17

## Decisions Locked In This Session

| # | Decision | Rationale |
|---|---|---|
| 1 | Rewrite wflow designer to React/TS from scratch | Single tech stack, shared state, antd 6 visuals |
| 2 | Custom lightweight workflow engine | Form-driven approval fits; avoids Flowable weight |
| 3 | MVP scope = form designer + sequential approval | Closed loop demo in 2-3 weeks |
| 4 | Hierarchical org (Company → Dept tree → User + Role + Leader) | Realistic for assign-by-dept-leader routing |
| 5 | Stateless JWT auth + Spring Security 6 | Ant-design-pro shell integrates cleanly |
| 6 | Single-repo monolith (`antflow/{frontend,backend,infra}`) | Fast iteration during MVP |
| 7 | MyBatis-Plus (3.5.5+) for persistence | Familiarity with wflow team; SB 3 / Jakarta compatible |
| 8 | @xyflow/react (React Flow) for process designer canvas | Align/edges/minimap out of the box |
| 9 | zustand + @tanstack/react-query for frontend state | Replaces Umi useModel where frequent updates bite |
| 10 | `@tanstack/react-query` owns server-state; Umi `request` only for ProComponents internals | Avoid double caching layers and conflicts with `useRequest` |
| 11 | Multi-assignee MVP semantics = **or-sign only**; `t_task.approval_mode` reserved column for future all-sign | User feedback: avoid later DDL change |
| 12 | Engine publishes must enforce form is PUBLISHED first | User feedback: avoid dangling flow on unpublished form |
| 13 | `AssigneeResolver` returns empty → engine throws `NoAssigneeFoundException`; start blocked until fixed | User feedback: prevent stuck instances |
| 14 | `JwtService` refuses to start if `${JWT_SECRET}` is empty or shorter than 32 bytes | User feedback: prevent weak-default deployment |
| 15 | Flyway V1 runs `CREATE EXTENSION IF NOT EXISTS ltree;` and seeds bcrypt-hashed admin password only | User feedback: avoid plaintext password leak in VCS |
| 16 | SchemaNode IDs are UUIDs and unique across the entire tree (not just siblings) | Avoid ID collisions when moving nodes between containers |
| 17 | `t_process_instance` and `t_task` carry `@Version` columns for optimistic locking | Approve/withdraw are transactional but concurrent double-submits are a real race |
| 18 | Flyway split: V1 = extensions + DDL, V3 = indexes (GIN/GIST) | PG index build locks tables; separate migration |
| 19 | `t_form_definition.code` UNIQUE constraint materialized in V1__init.sql DDL, not just in app code | DB-level uniqueness guarantee |
| 20 | MVP is strictly 1:1 between form and process definitions; "latest PUBLISHED process_definition" means `status = PUBLISHED AND form_def_id = ?` (look up by `t_form_definition.code`) | Engine start finds the flow via code, joins via form_def_id |

## High-Level Architecture

```
┌────────────────────────────────────┐        ┌──────────────────────────┐
│  React 18 + Umi Max 4 (frontend)   │ JWT    │ Spring Boot 3 (backend)  │
│  antd 6 + ProComponents 3 + Tailwind v4    │  Spring Security 6       │
│  @dnd-kit + @xyflow/react + zustand         │  MyBatis-Plus 3.5.5+     │
│  Form Designer / Process Designer   │ HTTPS  │  Flyway                  │
│  Form Runtime / Task Center         │ ────►  │  jjwt 0.12               │
│  Admin (org/role/user)              │        │  Custom Workflow Engine  │
│  (ant-design-pro shell, 3-file patch)        │  REST + springdoc-openapi│
└────────────────────────────────────┘        └────────────┬─────────────┘
                                                          │ JDBC
                                                          ▼
                                            ┌────────────────────────┐
                                            │  PostgreSQL 17         │
                                            │  JSONB for schemas     │
                                            │  ltree for dept paths  │
                                            └────────────────────────┘
```

## Repository Layout

```
antflow/
├── frontend/                           # cloned from ant-design-pro, port 8000
│   ├── src/pages/
│   │   ├── admin/                      # org/user/role management (ProTable)
│   │   ├── designer/form/              # FormDesigner
│   │   ├── designer/process/           # ProcessDesigner
│   │   ├── runtime/form/               # dynamic-form fill page
│   │   ├── tasks/                      # inbox / done / sent
│   │   └── proc/                       # instances I started
│   ├── src/components/
│   │   ├── FormRenderer/               # recursive renderer (designer preview + runtime)
│   │   └── form-fields/                # field components + ConfigPanels
│   ├── src/registry/
│   │   ├── formRegistry.ts
│   │   └── processNodeRegistry.ts
│   └── src/services/                   # npm run openapi → generated API client
├── backend/
│   ├── src/main/java/com/antflow/
│   │   ├── AntFlowApplication.java
│   │   ├── config/                     # SecurityConfig, OpenApiConfig, MybatisPlusConfig
│   │   ├── auth/                       # JwtService, JwtAuthFilter, AuthController
│   │   ├── org/                        # Company/Dept/User/Role + CRUD + tree APIs
│   │   ├── form/                       # FormDefinition persistence
│   │   ├── form/runtime/               # FormSubmission + data
│   │   ├── process/                    # ProcessDefinition + node schema
│   │   ├── engine/                     # ProcessEngine + dispatcher + handlers
│   │   ├── task/                       # Task + history + inbox APIs
│   │   └── common/                     # GlobalExceptionHandler, PageResp, BaseEntity
│   ├── src/main/resources/db/migration/
│   │   ├── V1__init.sql                # CREATE EXTENSION IF NOT EXISTS ltree; CREATE TABLE ...
│   │   │                              # Each UNIQUE constraint is at DB level, not only app code
│   │   │                              # (e.g. `code VARCHAR(64) NOT NULL UNIQUE` for form/proc).
│   │   ├── V2__seed.sql                # bcrypt-hashed admin user + roles only
│   │   └── V3__indexes.sql             # GIN on JSONB, BTREE on FKs, ltree GIST on dept.path
│   │                                  # Separated so PG index builds (which take table locks)
│   │                                  # don't compound with DDL on a fresh DB.
│   └── pom.xml
├── infra/
│   ├── docker-compose.yml              # postgres:17 (+ optional redis, minio)
│   ├── .env.example
│   └── seed/                           # default super-admin loader
├── docs/superpowers/specs/…
├── CLAUDE.md
└── README.md
```

## Data Model

All tables live in PostgreSQL 17. JSONB is used for open-ended schema; ltree is used for department paths.

### Organization

| Table | Key columns | Notes |
|---|---|---|
| `t_company` | id BIGSERIAL, name, created_at | Top of org tree |
| `t_department` | id, company_id, **parent_id**, **path ltree**, name, **leader_id** | `ltree` index for subtree queries |
| `t_role` | id, code UNIQUE, name | Built-in: `admin`, `user`. Seeded by Flyway V2. |
| `t_user` | id, dept_id FK, username UNIQUE, password_hash, display_name, email, status, created_at | Status: `ACTIVE`/`DISABLED` |
| `t_user_role` | user_id, role_id (composite PK) | Many-to-many |

### Form Designer (definitions + runtime)

| Table | Key columns | Notes |
|---|---|---|
| `t_form_definition` | id, code UNIQUE, name, version INT, **schema JSONB**, **settings JSONB**, status, created_by, created_at, updated_at | `status`: `DRAFT`/`PUBLISHED`/`DEPRECATED`. `version` increments on each publish — historical forms are not migrated. |
| `t_form_data` | id, form_def_id FK, **form_def_version** INT (snapshot), **data JSONB**, **status** (`DRAFT` or `SUBMITTED`), created_by, created_at | Runtime submissions. `form_def_version` records which form_def version the data was originally filled against; later form_def republishes do **not** retro-mutate this column. |

**`t_form_definition.schema` shape** (JSONB) — a tree stored as a flat top-level array:

```json
[
  { "id": "n1", "type": "text",       "label": "姓名",     "props": { "required": true } },
  { "id": "n2", "type": "date",       "label": "补卡日期", "props": {} },
  { "id": "n3", "type": "textarea",   "label": "理由",     "props": { "maxLength": 500 } },
  { "id": "n4", "type": "user_picker","label": "直属上级", "props": { "multiple": false } },
  { "id": "n5", "type": "span_layout","label": "明细表",   "props": { "columns": 2 },
    "children": [
      { "id": "n5a", "type": "date",   "label": "日期", "props": {} },
      { "id": "n5b", "type": "text",   "label": "原因", "props": {} }
    ]
  }
]
```

The schema at the top level is `SchemaNode[]`. Each `SchemaNode` is either a **leaf field** (a registered `FieldType` such as `text`/`date`/`user_picker`) or a **layout field** (`span_layout` or `table_list`) which carries `children: SchemaNode[]`. Leaf fields never carry children. Validation is registry-driven at the application layer, so adding a new field type does not require a DB migration.

**`t_form_definition.settings` shape**:
```json
{
  "committerRoles": ["user", "admin"],
  "adminRoles": ["admin"],
  "notify": { "type": "IN_APP", "title": "新审批通知" }
}
```

### Process Designer (definition + engine state)

| Table | Key columns | Notes |
|---|---|---|
| `t_process_definition` | id, form_def_id UNIQUE, version, **nodes JSONB**, **edges JSONB**, status, created_by | One-to-one with form_definition in MVP |
| `t_process_instance` | id, proc_def_id FK, form_data_id FK, status, current_node_id, **version INT DEFAULT 0** (`@Version`), started_by, started_at, finished_at | `status`: `RUNNING`/`APPROVED`/`REJECTED`/`WITHDRAWN`. `version` enables optimistic locking for concurrent approves — see note below. |
| `t_task` | id, proc_inst_id, node_id, **assignee_id**, status, **approval_mode** (`'OR_SIGN'` default), **version INT DEFAULT 0** (`@Version`), approved_by, approved_at, comment, created_at | `status`: `PENDING`/`APPROVED`/`REJECTED`/`SKIPPED`. `approval_mode` column is reserved for future `ALL_SIGN` mode; MVP only writes `OR_SIGN`. |
| `t_task_history` | id, proc_inst_id, from_node_id, to_node_id, action, operator_id, comment, created_at | Append-only audit |

**`t_process_definition.nodes` shape**:
```json
[
  { "id": "start",   "type": "start",    "x": 0,   "y": 0,   "props": {} },
  { "id": "n1",      "type": "approval", "x": 240, "y": 80,  "assignee": { "type": "user", "ids": [42] }, "props": {} },
  { "id": "end",     "type": "end",      "x": 480, "y": 80,  "props": {} }
]
```

**`t_process_definition.edges` shape**:
```json
[
  { "from": "start", "to": "n1" },
  { "from": "n1",    "to": "end" }
]
```

### Engine Module Layout

```
engine/
├── ProcessEngine.java          // public API: start, complete, reject, withdraw
├── NodeDispatcher.java         // routes node.type → handler
├── handler/
│   └── ApprovalNodeHandler.java   // MVP: only this kind of node
├── resolver/
│   ├── AssigneeResolver.java      // user / role / dept_leader → user_id list
│   └── ExpressionEngine.java      // stub for v1.x
└── api/EngineDto.java             // StartCmd, CompleteCmd, EngineResult
```

### Engine State Machine (MVP)

| Instance.status | Trigger | Next |
|---|---|---|
| (new)        | `POST /instances/start` with formDefCode + data | `RUNNING`, current_node=first post-start, first task created |
| `RUNNING`    | `POST /tasks/{id}/approve` | next task(s) created, instance remains `RUNNING`. If no successor node → `APPROVED` + finished_at |
| `RUNNING`    | `POST /tasks/{id}/reject`  | instance.status = `REJECTED` + finished_at |
| `RUNNING`    | `POST /instances/{id}/withdraw` (starter only, before first task claimed) | `WITHDRAWN` |
| `APPROVED` / `REJECTED` / `WITHDRAWN` | terminal |

`approve(taskId, operator, comment)` algorithm — runs inside a single `@Transactional` boundary on the service method:
1. Verify `task.status == PENDING` and `task.assignee_id == operator.id` (else 403).
2. Insert `t_task_history` (action = `APPROVE`).
3. Update `t_task.status = APPROVED`, `approved_at`, `approved_by`, `comment`.
4. Load `t_process_definition.edges`, find `next` nodes from this node.
5. Filter `next`: drop any node whose `type == 'end'` from "tasks to create". If the only remaining `next` nodes after filtering are `end` nodes, the instance terminates. A `t_task_history` row with `action = COMPLETE` is inserted to mark the implicit termination.
6. For each remaining `next` node: `AssigneeResolver.resolve(node.assignee)` must return a non-empty list or the entire transaction rolls back with `NoAssigneeFoundException`. Create one `t_task` row per resolved user with `approval_mode = OR_SIGN`. Set `process_instance.current_node_id = next.id`.
7. OR-sign short-circuit: mark all **other** PENDING tasks on the *just-completed* `current_node_id` as `SKIPPED` (with a `t_task_history` row each, `action = SKIP`, `comment = "OR-sign short-circuit"`). Leave pending tasks on `next_node_id` alone (they belong to the next batch).
8. If `next` set was empty after step 5: `process_instance.status = APPROVED`, `finished_at = now()`.

**Multi-assignee semantics in MVP — OR-sign only.** When `node.assignee` resolves to N users, we create N tasks and the **first** approver advances the instance; remaining tasks on the same node are SKIPPED via step 7. The `t_task` table carries an `approval_mode` column (`NOT NULL DEFAULT 'OR_SIGN'`) reserved for future `ALL_SIGN` mode without a DDL change. No code path in MVP reads this column differently — it exists only so we don't have to do a migration later.

`reject(taskId, operator, comment)` algorithm (`@Transactional`):
1. Same auth check as approve.
2. Insert `t_task_history` (action = `REJECT`).
3. Update `t_task.status = REJECTED`, `approved_at`, `approved_by`, `comment`.
4. Mark all **sibling tasks on the same node** `status = SKIPPED` (so they don't appear as pending). Record a history row per skipped task with `action = SKIP`.
5. Set `process_instance.status = REJECTED`, `finished_at = now()`.

`withdraw(instanceId, operator)` algorithm (`@Transactional`):
1. Verify `instance.started_by == operator.id` (else 403) and `instance.status == RUNNING`.
2. Reject withdraw if any `t_task` on the instance already has `status != PENDING` (the instance has already been acted on).
3. Update all pending tasks `status = SKIPPED`.
4. Insert a single `t_task_history` (action = `WITHDRAW`).
5. `process_instance.status = WITHDRAWN`, `finished_at = now()`.

`AssigneeResolver.resolve(assignee)` rules:
- `assignee.type == 'user'`: filter `ids` to active (`status = ACTIVE`) users only. **Disabled users are silently dropped** (a disabled assignee does NOT cause `NoAssigneeFoundException` — it just reduces the assignee list). If the resulting list is empty, throw `NoAssigneeFoundException`.
- `assignee.type == 'role'`: load `t_user_role` JOIN `t_user WHERE status = ACTIVE` for the role. If empty, throw `NoAssigneeFoundException`.
- `assignee.type == 'dept_leader'`: read `t_department` row. If `leader_id` is null or the leader is non-active, throw `NoAssigneeFoundException`.

`NoAssigneeFoundException` is a `BizException` mapped by `GlobalExceptionHandler` to HTTP `422` with body `{ code: 'NO_ASSIGNEE', message, nodeId }`. The `/instances/start` endpoint catches it explicitly; because the engine call is also `@Transactional`, no partial state is committed.

**`end` node handling.** The `end` node never has assignee resolution and never produces tasks. Two enforcement points:
1. The `approve()` algorithm above filters `end` out of `next` before resolver runs.
2. As a safety net, `NodeDispatcher.dispatch(node)` for `type == 'end'` throws `BadNodeTypeException` if called at all (an `end` reaching `dispatch` would indicate a malformed graph — edges that go straight past `end`, etc.).

**Concurrency / optimistic locking (B).** All engine write paths (`approve`, `reject`, `withdraw`, `start`) are `@Transactional` with `READ_COMMITTED` isolation. Both `t_process_instance.version` and `t_task.version` columns are declared with `@Version`. MyBatis-Plus' optimistic-locker plugin (or a manual `WHERE id = ? AND version = ?` on the row) detects concurrent double-clicks:
- If a request comes in based on a stale instance version, the engine returns `409 STALE_INSTANCE`.
- The client refetches `GET /api/instances/{id}` and retries idempotently (already-completed tasks return `409 ALREADY_DECIDED`).

MVP excludes: parallel branches, ALL_SIGN (counter-sign), reject-back-to-previous, dynamic add-step, timeouts, escalation. Each becomes a new `NodeHandler` in v1.x without changing `ProcessEngine`.

## REST API Surface

Conventions: kebab-case paths, JSON in/out, `Authorization: Bearer <jwt>` except on `/api/auth/login`. `404` and `500` use the shared error envelope `{ code, message, traceId }`.

### Auth
- `POST /api/auth/login` — body `{ username, password }` → `{ accessToken, user }`
- `GET  /api/auth/me` — current user with roles + dept

### Organization
- `GET/POST /api/companies`
- `GET /api/departments?companyId=&parentId=` (returns tree or flat per `?flat=true`)
- `GET/POST /api/users[/{id}]`, `PUT /api/users/{id}/roles`, `PUT /api/users/{id}/password`
- `GET/POST /api/roles`

### Form Designer + Runtime
- `GET/POST /api/forms/definitions`, `GET /api/forms/definitions/{id}`
- `POST /api/forms/definitions/{id}/publish` (validates schema + transitions `DRAFT → PUBLISHED`)
- `POST /api/forms/data` — body `{ formDefCode, data, status?: 'DRAFT' | 'SUBMITTED' }` → `{ dataId }`. **This endpoint does NOT start a process instance** — it only persists the form data, intended for "暂存/测试" (draft / preview-only) flows. Status defaults to `DRAFT`. A `SUBMITTED` row written here has no process_instance.
- `GET  /api/forms/data?createdBy=me&formDefCode=` — list my submissions
- In production traffic, **the only path that creates a `t_form_data` linked to a process instance is `/api/instances/start`**. This separation keeps test/draft data out of the audit history.

### Process Designer
- `GET/POST /api/processes/definitions`, `GET /api/processes/definitions/by-form/{formDefId}`
- `POST /api/processes/definitions/{id}/publish` — server-side guard: the associated `form_definition.status` MUST be `PUBLISHED` or this returns `422 FOR_FORM_NOT_PUBLISHED`. Prevents deploying a flow onto a draft form.

### Engine
- `POST /api/instances/start` — body `{ formDefCode, data }`. Internally (one transaction):
  1. Resolve `formDef` by `t_form_definition.code = formDefCode` (UNIQUE-indexed). 404 if missing.
  2. Resolve the **latest `PUBLISHED`** `t_process_definition` by `t_process_definition.form_def_id = formDef.id AND status = 'PUBLISHED'`. 404 if none. (MVP is strict 1:1 between form and process definitions; "latest PUBLISHED" is a single-row lookup.)
  3. Create `t_form_data` row with `status = SUBMITTED`, `form_def_version = <formDef.version>` (snapshot of the form_def version that this run fills against).
  4. Create `t_process_instance` linked to both `form_data_id` and `process_definition_id` with `status = RUNNING`, `current_node_id = <first node after start>`.
  5. Run the engine's `start` step on the first active node: `AssigneeResolver.resolve(node.assignee)` must succeed or the entire transaction rolls back with `NO_ASSIGNEE`.
  6. Insert `t_task_history` row with `action = START`, `from_node_id = 'start'`, `to_node_id = first_node_id`.
  Response: `{ instanceId, formDataId, firstTaskIds }`.
- `GET  /api/instances?status=&startedBy=me`
- `GET  /api/instances/{id}` (with full task list + history)
- `GET  /api/instances/{id}/history`

### Tasks
- `GET  /api/tasks?assignee=me&status=PENDING`
- `POST /api/tasks/{id}/approve` — body `{ comment }`
- `POST /api/tasks/{id}/reject`  — body `{ comment }`
- `POST /api/instances/{id}/withdraw` — starter only; 422 if first task already acted on

## Frontend Module Design

### Component Architecture

Three reusable layers:

1. **Field registry** — `src/registry/formRegistry.ts` exports `Record<string, FieldType>`. Each entry bundles a renderer, config panel, default props, and optional validator.
2. **FormRenderer** — recursive component that walks `schema: SchemaNode[]` and renders each via `registry[type].Component`. Three modes:
   - `mode="designer-preview"` — read-only mock
   - `mode="runtime-fill"` — controlled, validates, `onSubmit(data)`
   - `mode="readonly"` — read-only (used on approver side to view submission)
3. **ConfigPanel binding** — designer right-pane uses `registry[type].ConfigPanel` to edit props of the currently selected node.

### Form Designer Page

`/designer/form/:id` layout:
- **Left palette** — list of registered field types (cards with icon + label), drag-onto-canvas via `@dnd-kit`.
- **Center canvas** — recursive tree, MVP is `1-col list + optional SpanLayout children` (no free-grid positioning in v1).
- **Right inspector** — selected node's ConfigPanel.
- **Top toolbar** — Save Draft, Publish, Undo, Redo, Preview.

State via zustand `useFormDesignerStore`:
```ts
type Store = {
  schema: SchemaNode[];
  selectedId: string | null;
  dragging: { source: 'palette' | 'tree'; fieldType?: string } | null;
  history: { past: SchemaNode[][]; future: SchemaNode[][] };
  addNode(parentId: string | null, type: string): void;
  removeNode(id: string): void;
  updateNode(id: string, patch: Partial<SchemaNode>): void;
  select(id: string | null): void;
  undo(): void;
  redo(): void;
};
```

`history` enables Undo/Redo.

**Performance note (MVP):** the history stores full `SchemaNode[][]` snapshots (Zustand immutability gives shallow copy on top-level array, but each node is reconstructed on `setState`). With 50+ fields or deep nesting this can cause noticeable lag on every drag tick. Acceptable for MVP — schema complexity is bounded by what a human designs in one sitting. **TODO:** if profiling shows regressions, swap to `immer` (zustand's middleware) or `immutability-helper` patches so each mutation pushes the *delta*, not the whole tree. Tracked as `ANTFLOW_DESIGNER_PERF` in the repo's known-issues list.

**Node ID uniqueness (A).** Every `SchemaNode.id` is a UUID generated by `addNode()` and must be unique across the *entire* tree, not just within siblings. The store exposes `findById(id: string): SchemaNode | null` (used by `updateNode`, `removeNode`, and the right-inspector binding) and `getPath(id): SchemaNode[]` (used by `moveNode` in v1.x). When a layout container is removed, its children are recursively re-rooted or deleted per user choice — never silently duplicated.

**Performance note (MVP):** the history stores full `SchemaNode[][]` snapshots (Zustand immutability gives shallow copy on top-level array, but each node is reconstructed on `setState`). With 50+ fields or deep nesting this can cause noticeable lag on every drag tick. Acceptable for MVP — schema complexity is bounded by what a human designs in one sitting. **TODO:** if profiling shows regressions, swap to `immer` (zustand's middleware) or `immutability-helper` patches so each mutation pushes the *delta*, not the whole tree. Tracked as `ANTFLOW_DESIGNER_PERF` in the repo's known-issues list.

### Process Designer Page

`/designer/process/:formDefId` uses **React Flow v12** (npm package is now `@xyflow/react` — the v11 `reactflow` package is deprecated). Key API differences from v11 to keep in mind:

- Imports come from `@xyflow/react`: `ReactFlow`, `Background`, `Controls`, `MiniMap`, `useNodesState`, `useEdgesState`, `NodeProps`, `Handle`, `Position`, `Node`, `Edge`.
- A custom node component receives `NodeProps<MyNode>` which is `{ id, data, type, isConnecting, selected, dragging, xPos, yPos, isDragging, zIndex }` — note `xPos`/`yPos` on the props, while the node's stored position is still `position: { x, y }`.
- `nodeTypes` registration is unchanged.

Example custom `ApprovalNode` for MVP:

```tsx
import { NodeProps, Handle, Position, Node } from '@xyflow/react';

export type ApprovalNodeData = {
  label: string;
  assignee: { type: 'user' | 'role' | 'dept_leader'; ids: (string | number)[] };
  errorCount?: number;
};
export type ApprovalFlowNode = Node<ApprovalNodeData, 'approval'>;

export function ApprovalNode({ data, selected }: NodeProps<ApprovalFlowNode>) {
  return (
    <div className={selected ? 'af-node af-node--selected' : 'af-node'}
         style={{ borderColor: data.errorCount ? '#ff4d4f' : undefined }}>
      <Handle type="target" position={Position.Top} />
      <div>{data.label}</div>
      <small>{data.assignee.type} · {data.assignee.ids.length} 人</small>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

// registration
const nodeTypes = { approval: ApprovalNode, start: StartNode, end: EndNode };
```

Designer page anatomy:
- **Nodes** — `start`, `approval`, `end` for MVP (plus `start` and `end` share a generic render shape). Custom node types render label + assignee summary + status (draft/error).
- **Edges** — drag from one node's `Handle` to another to create; click edge to delete.
- **Right inspector** — selected node's ConfigPanel (`ApprovalNodeConfig` rewritten from wflow's Vue version) with `AssigneePicker`.
- **Validation** — on Save Draft / Publish, run each node's `validate()` and highlight offenders with red border (driven by setting `data.errorCount` on the node).
- **Save payload** — translate React Flow's `{ nodes, edges }` (with `position`) into the database shape `{ nodes: [...{ id, type, x, y, assignee, props }], edges: [...{ from, to }] }`.

### Assignee Picker

`src/components/AssigneePicker.tsx`:
- Mode `user` — antd Select with `mode="multiple"`, `showSearch`, fetches `/api/users?keyword=`
- Mode `role` — antd Select fetches `/api/roles`
- Mode `dept_leader` — DeptPicker; resolves to `dept.leader_id` server-side, displayed read-only

### Server-State Boundaries (`react-query` vs Umi `request`)

The project deliberately runs **two HTTP layers** and the boundary is sharp:

| Use this | For |
|---|---|
| `@tanstack/react-query` (`useQuery`, `useMutation`, `useInfiniteQuery`) | **All** hand-written data fetching. Inbox, history, instance detail, AssigneePicker keyword search, designer loads and saves — everything not baked into a ProComponent. |
| Umi `request` (the built-in one from `@umijs/max`) | **Three** sanctioned places only: (a) `app.tsx`'s `getInitialState()` calling `/api/auth/me`; (b) `requestErrorConfig.ts` `errorHandler`; (c) `ProTable`'s built-in `request` prop, where ProComponents expect Umi's request shape. ProForm's submit prop similarly expects an Umi-style async function — do not rewrap. |
| Direct `fetch` or `axios` calls inside components | **Forbidden** outside `src/services/api.ts`; even there prefer `request` so the interceptor stack (auth header, 401 redirect, traceId) applies. |

Why the rule: Umi ships its own caching in `useRequest` and `ProTable.request`. Mixing it with `react-query` caches produces two stale copies of the same data and double network requests. By sending hand-written code straight to `react-query` and reserving Umi `request` for the `ProComponents` integration points, both layers stay consistent.

Configuration:
- `src/requestErrorConfig.ts` — Umi `request` interceptor (token header + 401 redirect). Untouched.
- `src/services/api.ts` — exports a thin `useApiClient()` returning `{ get, post, put, del }` wrappers built on Umi `request` (so the interceptor still applies) but typed for `react-query` to consume:
  ```ts
  const qc = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: 1 } } });
  // in component:
  const { data } = useQuery({ queryKey: ['tasks','me','PENDING'], queryFn: () => api.get('/api/tasks', { assignee: 'me', status: 'PENDING' }) });
  ```
- `app.tsx` wraps `<App>` with `<QueryClientProvider>` from `@tanstack/react-query`.

### Field Types (MVP)

Leaf fields: `text`, `textarea`, `number`, `money`, `date`, `date_range`, `select`, `multi_select`, `user_picker`, `dept_picker`, `file_upload`, `description`.

Layout fields (have `children`): `span_layout` (multi-column row, 1–4 cols), `table_list` (明细表 with repeating row group).

Each has renderer + config panel + default props. Layout fields render their `children` recursively through the same `FormRenderer`.

### Node Types (MVP)

`start`, `approval`, `end`

## Ant-Design-Pro Shell Integration

Three files are modified to plug the React shell onto the new backend; everything else from the upstream template is kept as-is.

### `src/app.tsx`
Replace mock `getInitialState()`:
```ts
export async function getInitialState() {
  const token = localStorage.getItem('antflow-token');
  if (!token) return {};
  try {
    const me = await request<MeResp>('/api/auth/me');
    return { currentUser: me, settings: defaultSettings };
  } catch (e) {
    localStorage.removeItem('antflow-token');
    return {};
  }
}
```

### `src/requestErrorConfig.ts`
Add `requestInterceptors` to inject `Authorization: Bearer ${token}`; on 401, clear token + redirect to `/user/login`.

### `src/access.ts`
```ts
export default (initialState: any) => {
  const roles: string[] = initialState?.currentUser?.roles ?? [];
  return {
    canAdmin: roles.includes('admin'),
  };
};
```

In MVP, all of org management, form design, and process design are gated behind `canAdmin`. End-users (role `user`) only see fill-form and task-center pages.

## Cross-Cutting Concerns

### Error Handling
- `GlobalExceptionHandler` (`@RestControllerAdvice`) returns `{ code, message, traceId, fieldErrors? }` for known exceptions (`BizException`, `MethodArgumentNotValidException`).
- 401 carries `WWW-Authenticate: Bearer`; frontend redirects to `/user/login`.
- 422 from `/tasks/*/approve` returns `fieldErrors[]` to allow the form to highlight the offending task/comment.
- Designer save failures → toast + preserve canvas state; publish failures → red border on offending nodes with error tooltip from server response.

### Security
- JWT: HS256, 24h TTL, secret from `${JWT_SECRET}` env var (no default). `JwtService` constructor **fails fast** if the secret is missing or shorter than 32 bytes by throwing `IllegalStateException` — this prevents the app from starting in production with a weak or empty key. jjwt 0.12.x; `setAllowedClockSkewSeconds(30)` so legitimate users don't see 401 from minor clock drift.
- BCrypt for password hashing; seed migration `V2__seed.sql` must embed a pre-hashed admin password — no plaintext.
- Spring Security method-level `@PreAuthorize("hasRole('admin')")` on org/role/permission-sensitive endpoints.
- CORS allowlist: dev `http://localhost:8000` only; production uses a comma-separated `${CORS_ALLOWED_ORIGINS}` env var (no permissive default).
- Login rate limit: MVP uses an in-memory Bucket4j token bucket per IP (5 attempts / minute, 30 / hour) on `POST /api/auth/login`. Sufficient for single-node deployment; switch to Redis-backed in v1.x.

### Observability (minimal)
- Standard Spring Boot `/actuator/health` and `/actuator/info`.
- All errors carry `traceId` (UUID generated in `GlobalExceptionHandler`).
- Structured logback JSON output (drop-in `logstash-logback-encoder`).

### Performance
- Designer canvas uses `React.memo` on field components + zustand selectors to avoid full re-renders.
- React Flow uses only necessary node types and disables unnecessary features in MVP.
- MyBatis-Plus pagination on `t_task` and `t_form_data` lists.
- Optional Redis (later) for hot task inbox caching — out of MVP scope.

## Testing Strategy

### Backend
- **Unit** — services + engine handlers with Mockito. Engine `approve()` algorithm covered with golden-path + 4 negative paths: wrong operator, already-approved task, no successor (→ APPROVED), `NoAssigneeFoundException` (→ 422). Reject sibling SKIP behavior covered.
- **Repository** — Testcontainers (PG 17 image) runs Flyway migrations; write/read/delete per entity. Crucially exercises the `@Version` columns: insert, update with matching version (success), update with stale version (throws `OptimisticLockException`).
- **API** — `@SpringBootTest` + MockMvc for CRUD; full happy-path start→approve via `RestAssured`. A separate test asserts that `POST /api/instances/start` returns `422 FOR_FORM_NOT_PUBLISHED` when the form is still `DRAFT`, and `422 NO_ASSIGNEE` when the node has an empty user list.
- **Engine integration** — single test per phase-end: starts container, loads a fixture `process_definition`, fires HTTP, asserts DB rows. One combined scenario: 2-assignee OR-sign node → first approver advances → second task marked `SKIPPED` with `t_task_history` row.
- **Security** — JwtService bean creation fails the Spring context when secret missing/short. Bucket4j login rate limit verified with a 6-call burst returning `429` after the 5th minute-bucket.

### Frontend
- **vitest** — `formRegistry` payload shape + `FormRenderer` recursion for representative schema.
- **playwright** — one end-to-end: login → design form "补卡申请" → publish → start instance → approve as assignee → instance shows APPROVED.

### CI
- One GitHub Actions / equivalent workflow: `lint (backend & frontend) + backend test + frontend test + frontend build`. Docker compose-up PG required for backend tests via Testcontainers (CI service container).

## Implementation Roadmap (sequential phases)

| Phase | Scope | Demonstrable |
|---|---|---|
| **P0 — baseline** | repo scaffold, `docker-compose` for PG 17, Flyway init schema, Spring Security 6 + JWT login working, frontend shell login page wired, three-file patch on ant-design-pro | Login → empty antd-pro home |
| **P1 — org** | company/dept/user/role CRUD via ProTable, menu filtered by `access.canAdmin` | Admin can register a colleague and assign them to a department |
| **P2 — form designer** | all MVP field types in registry, @dnd-kit palette/canvas/inspector, save-as-draft, publish, preview | Design a "补卡申请", save, publish |
| **P3 — form runtime** | FormRenderer controlled mode, validation, submit, list-my-submissions | alice fills and submits the form |
| **P4 — process designer + engine** | processNodeRegistry (`start`/`approval`/`end`), React Flow canvas, AssigneePicker, publish, engine `start`/`approve`/`reject`/`withdraw`, OR-sign short-circuit, `@Version` locking | Wire 1-level approval onto the form, publish |
| **P5 — task center** | my inbox / done / sent, approve/reject/withdraw, instance detail with history | alice submits → bob gets task → bob approves → instance APPROVED |

Each phase ends in a runnable demo and a green CI.

## Explicit Non-Goals (MVP)

These are intentionally left out of v1 and noted here so reviewers don't assume them:

- Parallel branches, multi-counter-sign, reject-back-to-previous
- Dynamic form permissions per node
- Expression-based condition nodes
- Process versioning / migration of in-flight instances
- Mobile-optimized separate runtime
- File upload to S3/MinIO (MVP stores files under a configurable `uploads/` directory via standard `MultipartFile`; no abstraction layer in MVP — introduce `FileStorage` interface when adding S3/MinIO)
- Multi-tenant isolation (single-tenant in MVP; `t_company` row exists but cross-tenant guarding is not enforced)
- OAuth2 / external IdP
- Redis caching
- Internationalization (zh-CN only initially; i18n scaffold left for v1.x)
- Print, export, statistics dashboards

## Open Questions for v1.x (parked, not blocking this spec)

- Should `t_form_definition.settings.commiterRoles` use role codes or role ids? (Leaning role codes for portability across role renames.)
- Should `t_task` carry a `due_at` for SLA tracking? (Not in MVP; consider with P6 notifications.)
- Should process versions be immutable once any instance exists? (Trending yes — implement when first version migration is needed.)
