# AntFlow Mobile Approval Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已完成的平台基础和移动壳层上交付动态表单、草稿、文件上传、流程发起、待办处理、流程详情、错误恢复和端到端质量闭环。

**Architecture:** 后端新增移动工作流 facade，把现有 FormData、ProcessEngine、Task 和快照数据转换为移动 DTO，并集中执行资源授权和 allowedActions 计算。移动端使用字段注册表递归渲染 schema；草稿分为服务端显式草稿与本地恢复副本；所有状态变更使用幂等 key 并由 TanStack Query 精确失效缓存。

**Tech Stack:** Java 17/Spring Boot/MyBatis-Plus/PostgreSQL, React 19/TypeScript/Vite, Ant Design Mobile, TanStack Query, Zustand, Vitest/Testing Library/Playwright, browser performance APIs.

---

## File Map

### Backend

- `backend/src/main/java/com/antflow/mobile/workflow/*`：草稿、上传、实例、任务移动 facade 和 DTO。
- `backend/src/main/resources/db/migration/V11__mobile_drafts_and_files.sql`：草稿更新字段和文件元数据。
- `backend/src/test/java/com/antflow/mobile/workflow/*`：权限、幂等、草稿、上传、启动和审批测试。

### Mobile

- `mobile/src/features/forms/*`：字段注册表、动态渲染、草稿、自选人、确认和成功。
- `mobile/src/features/tasks/*`：三视图任务中心、详情、同意和驳回。
- `mobile/src/features/processes/*`：实例进度和撤回。
- `mobile/src/shared/recovery/*`：离线恢复和写请求状态。
- `mobile/src/shared/telemetry/*`：错误、性能和 traceId 上报。
- `mobile/e2e/*`：完整审批闭环和视觉回归。

## Task 1: Add Mobile Draft And File Persistence

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__mobile_drafts_and_files.sql`
- Create: `backend/src/main/java/com/antflow/mobile/workflow/MobileFile.java`
- Create: `backend/src/main/java/com/antflow/mobile/workflow/MobileFileMapper.java`
- Create: `backend/src/main/java/com/antflow/mobile/workflow/MobileDraftService.java`
- Test: `backend/src/test/java/com/antflow/mobile/workflow/MobileDraftServiceTest.java`

- [ ] **Step 1: Write draft ownership tests**

Test create, update, delete, template-unpublished read-only behavior and a second user receiving `AccessDeniedException`.

- [ ] **Step 2: Add V11 migration**

```sql
ALTER TABLE t_form_data ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE t_mobile_file (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id BIGINT NOT NULL REFERENCES t_user(id),
    original_name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX ix_mobile_file_owner ON t_mobile_file(owner_id, created_at DESC);

CREATE TABLE t_form_data_file (
    form_data_id BIGINT NOT NULL REFERENCES t_form_data(id) ON DELETE CASCADE,
    file_id UUID NOT NULL REFERENCES t_mobile_file(id),
    field_id VARCHAR(64) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (form_data_id, file_id)
);
CREATE INDEX ix_form_data_file_file ON t_form_data_file(file_id);
```

Use existing `t_form_data(status='DRAFT')` rather than creating a second draft table.

- [ ] **Step 3: Implement explicit draft methods**

```java
Long create(String formCode, JsonNode data, long userId);
FormData update(long draftId, JsonNode data, long userId);
void delete(long draftId, long userId);
List<MobileDraftDto> list(long userId);
MobileDraftDto get(long draftId, long userId);
void deleteAfterSubmit(long draftId, long userId);
```

`update` must require DRAFT status and matching `created_by`; it updates `data` and `updated_at`. `deleteAfterSubmit` runs only after `ProcessEngine.start` returns the official `formDataId`, so a draft is never converted into a second SUBMITTED row.

- [ ] **Step 4: Verify and commit**

```powershell
Set-Location backend
mvn -B -Dtest=MobileDraftServiceTest test
Set-Location ..
git add backend/src/main/resources/db/migration/V11__mobile_drafts_and_files.sql backend/src/main/java/com/antflow/mobile/workflow backend/src/test/java/com/antflow/mobile/workflow
git commit -m "功能(后端): 增加移动草稿与文件元数据"
```

## Task 2: Add Safe Mobile File Upload And Signed Reads

**Files:**
- Create: `backend/src/main/java/com/antflow/mobile/workflow/MobileFileService.java`
- Create: `backend/src/main/java/com/antflow/mobile/workflow/MobileFileController.java`
- Create: `backend/src/main/java/com/antflow/mobile/workflow/FileStorage.java`
- Create: `backend/src/main/java/com/antflow/mobile/workflow/LocalFileStorage.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/antflow/mobile/workflow/MobileFileServiceTest.java`

- [ ] **Step 1: Write upload security tests**

Cover empty file, >10 MB, executable signature, mismatched content type, duplicate content, owner read and unrelated-user denial.

- [ ] **Step 2: Define storage boundary**

```java
public interface FileStorage {
    StoredObject put(String storageKey, InputStream content, long size) throws IOException;
    Resource get(String storageKey);
    void delete(String storageKey);
}
```

Keep local filesystem implementation in this plan; the interface permits S3 later without changing services.

- [ ] **Step 3: Implement upload endpoint**

```text
POST /api/mobile/files                 multipart upload
GET  /api/mobile/files/{id}            metadata for owner/instance participant/admin
GET  /api/mobile/files/{id}/content    authenticated file response
DELETE /api/mobile/files/{id}          owner, only before submission
```

Return:

```java
public record MobileFileDto(UUID id, String name, String contentType,
                            long size, String contentUrl) {
}
```

Never return storageKey.

- [ ] **Step 4: Configure limits**

```yaml
spring.servlet.multipart:
  max-file-size: 10MB
  max-request-size: 12MB
antflow.mobile.files:
  directory: ${MOBILE_FILE_DIRECTORY:./data/mobile-files}
  allowed-types: image/jpeg,image/png,application/pdf
```

- [ ] **Step 5: Verify and commit**

```powershell
Set-Location backend
mvn -B -Dtest=MobileFileServiceTest test
Set-Location ..
git add backend/src/main/java/com/antflow/mobile/workflow backend/src/main/resources/application.yml backend/src/test/java/com/antflow/mobile/workflow
git commit -m "功能(后端): 增加移动附件安全上传与授权读取"
```

## Task 3: Add Mobile Workflow Facade And DTO Endpoints

**Files:**
- Create: `backend/src/main/java/com/antflow/mobile/workflow/MobileWorkflowDtos.java`
- Create: `backend/src/main/java/com/antflow/mobile/workflow/MobileWorkflowService.java`
- Create: `backend/src/main/java/com/antflow/mobile/workflow/MobileWorkflowController.java`
- Test: `backend/src/test/java/com/antflow/mobile/workflow/MobileWorkflowServiceTest.java`

- [ ] **Step 1: Write facade tests**

Cover:

- Start uses current published form/process, links ready files to the new submitted FormData, then deletes the optional draft.
- Detail reads `processSnapshot`.
- Pending task query only returns current assignee.
- Process detail returns `canWithdraw` from server rules.
- Task detail returns `allowedActions` and legal reject targets.
- Entity JSON strings are converted to JsonNode.

- [ ] **Step 2: Define DTOs**

```java
public record MobileTaskDto(Long id, Long instanceId, String formName,
    String applicantName, String applicantDepartment, String nodeName,
    String taskStatus, String instanceStatus, OffsetDateTime createdAt) {
}

public record MobileTaskDetailDto(MobileTaskDto task, JsonNode schema,
    JsonNode formData, JsonNode processSnapshot, List<MobileHistoryDto> history,
    List<String> allowedActions, List<RejectTargetDto> rejectTargets,
    List<MobileFileDto> files) {
}

public record MobileInstanceDetailDto(Long id, String status, String formName,
    JsonNode schema, JsonNode formData, JsonNode processSnapshot,
    List<MobileHistoryDto> history, boolean canWithdraw, List<MobileFileDto> files) {
}
```

- [ ] **Step 3: Add mobile workflow routes**

```text
POST /api/mobile/drafts
PUT /api/mobile/drafts/{id}
DELETE /api/mobile/drafts/{id}
GET /api/mobile/drafts
POST /api/mobile/instances
GET /api/mobile/instances
GET /api/mobile/instances/{id}
POST /api/mobile/instances/{id}/withdraw
GET /api/mobile/tasks?view=pending|done
GET /api/mobile/tasks/{id}
POST /api/mobile/tasks/{id}/approve
POST /api/mobile/tasks/{id}/reject
```

- [ ] **Step 4: Keep engine transactions authoritative**

Facade calls `ProcessEngine.start/approve/reject/withdraw`; it must not duplicate task advancement. For start, read and authorize the optional draft, call `ProcessEngine.start`, take `formDataId` from the result, insert `t_form_data_file` rows for ready files owned by the starter, and delete the DRAFT row. The complete sequence is one outer transaction, so link/delete failure rolls back the instance and tasks.

- [ ] **Step 5: Verify and commit**

```powershell
Set-Location backend
mvn -B -Dtest=MobileWorkflowServiceTest,ProcessEngineTreeTest,TaskOperationServiceTest test
Set-Location ..
git add backend/src/main/java/com/antflow/mobile/workflow backend/src/test/java/com/antflow/mobile/workflow
git commit -m "功能(后端): 增加移动审批工作流接口"
```

## Task 4: Establish The Mobile Field Registry

**Files:**
- Create: `mobile/src/features/forms/schema/types.ts`
- Create: `mobile/src/features/forms/schema/fieldRegistry.ts`
- Create: `mobile/src/features/forms/schema/validators.ts`
- Create: `mobile/src/features/forms/schema/fieldRegistry.test.ts`
- Create: `mobile/src/features/forms/components/DynamicFormRenderer.tsx`
- Create: `mobile/src/features/forms/components/DynamicFormRenderer.test.tsx`

- [ ] **Step 1: Define recursive schema and values**

```ts
export type MobileSchemaNode = {
  id: string;
  type: FieldTypeCode;
  label?: string;
  props?: Record<string, unknown>;
  children?: MobileSchemaNode[];
};

export type MobileFormValues = Record<string, unknown>;
export type FieldMode = 'fill' | 'readonly';
```

- [ ] **Step 2: Write registry contract tests**

Assert exactly 14 registered types, unique type codes, validators return field-id keyed errors, and unknown types render an explicit unsupported-field state.

- [ ] **Step 3: Define field contract**

```ts
export type MobileFieldDefinition = {
  type: FieldTypeCode;
  Component: ComponentType<MobileFieldProps>;
  validate(node: MobileSchemaNode, value: unknown): string | null;
  summarize(node: MobileSchemaNode, value: unknown): string;
};
```

- [ ] **Step 4: Implement recursive renderer**

Renderer receives the full value object. A leaf reads/writes `values[node.id]`; layout fields delegate children without wrapping the child value a second time. This explicitly avoids the current nested double-lookup risk.

- [ ] **Step 5: Verify and commit**

```powershell
Set-Location mobile
npm test -- src/features/forms/schema src/features/forms/components/DynamicFormRenderer.test.tsx
npm run build
Set-Location ..
git add mobile/src/features/forms/schema mobile/src/features/forms/components/DynamicFormRenderer.tsx mobile/src/features/forms/components/DynamicFormRenderer.test.tsx
git commit -m "功能(移动端): 建立动态表单字段注册与递归渲染"
```

## Task 5: Implement Leaf Mobile Fields

**Files:**
- Create: `mobile/src/features/forms/fields/TextField.tsx`
- Create: `mobile/src/features/forms/fields/TextareaField.tsx`
- Create: `mobile/src/features/forms/fields/NumberField.tsx`
- Create: `mobile/src/features/forms/fields/MoneyField.tsx`
- Create: `mobile/src/features/forms/fields/DateField.tsx`
- Create: `mobile/src/features/forms/fields/DateRangeField.tsx`
- Create: `mobile/src/features/forms/fields/SelectField.tsx`
- Create: `mobile/src/features/forms/fields/MultiSelectField.tsx`
- Create: `mobile/src/features/forms/fields/DescriptionField.tsx`
- Create: `mobile/src/features/forms/fields/fields.test.tsx`

- [ ] **Step 1: Inspect Ant Design Mobile APIs**

Check installed type definitions/examples for `Input`, `TextArea`, `NumberKeyboard`, `DatePicker`, `Picker`, `Selector` and `Form`. Do not assume desktop antd props apply.

- [ ] **Step 2: Write field interaction tests**

For each type test fill mode, readonly mode, required error, value change and summary text. Use stable labels from real Chinese approval forms.

- [ ] **Step 3: Implement nine fields**

Common label wrapper:

```tsx
<FieldShell label={node.label} required={required} error={error}>
  <FieldControl value={typedValue} onChange={(next) => onValueChange(node.id, next)} />
</FieldShell>
```

Money stores a decimal string, not floating point cents. Dates store ISO date/time strings. Date ranges store `[startIso, endIso]`.

- [ ] **Step 4: Verify and commit**

```powershell
npm test -- src/features/forms/fields/fields.test.tsx
npm run lint
npm run build
Set-Location ..
git add mobile/src/features/forms/fields mobile/src/features/forms/schema/fieldRegistry.ts
git commit -m "功能(移动端): 实现基础动态表单字段"
```

## Task 6: Implement Picker, Upload And Layout Fields

**Files:**
- Create: `mobile/src/features/forms/fields/UserPickerField.tsx`
- Create: `mobile/src/features/forms/fields/DeptPickerField.tsx`
- Create: `mobile/src/features/forms/fields/FileUploadField.tsx`
- Create: `mobile/src/features/forms/fields/SpanLayoutField.tsx`
- Create: `mobile/src/features/forms/fields/TableListField.tsx`
- Create: `mobile/src/features/forms/fields/advanced-fields.test.tsx`
- Create: `mobile/src/features/forms/files.api.ts`

- [ ] **Step 1: Write advanced-field tests**

Cover user search, department selector, upload progress/failure/remove, span layout single-column fallback, and table-list add/edit/delete with min/max rows.

- [ ] **Step 2: Implement internal pickers without a contacts page**

User picker uses a searchable popup and returns numeric user IDs. Department picker returns numeric department IDs. Both are field controls only and do not add navigation entries.

- [ ] **Step 3: Implement upload queue**

```ts
export type UploadItem = {
  localId: string;
  file: File;
  status: 'queued' | 'uploading' | 'ready' | 'failed';
  progress: number;
  remote?: MobileFile;
  error?: string;
};
```

Form value stores ready `MobileFileDto` objects only. Failed/queued items stay in local UI state and block submission.

- [ ] **Step 4: Implement mobile layout behavior**

`span_layout` always stacks children at widths below 600px. `table_list` renders each row as a collapsible card with row number and delete action; do not use horizontal desktop Table.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/forms/fields/advanced-fields.test.tsx
npm run build
Set-Location ..
git add mobile/src/features/forms/fields mobile/src/features/forms/files.api.ts mobile/src/features/forms/schema/fieldRegistry.ts
git commit -m "功能(移动端): 实现选人附件与移动布局字段"
```

## Task 7: Implement Draft Recovery And Form Fill Page

**Files:**
- Create: `mobile/src/features/forms/drafts.api.ts`
- Create: `mobile/src/features/forms/recoveryDraft.store.ts`
- Create: `mobile/src/features/forms/FormFillPage.tsx`
- Create: `mobile/src/features/forms/FormFillPage.test.tsx`
- Create: `mobile/src/features/forms/DraftListPage.tsx`
- Create: `mobile/src/features/forms/DraftListPage.test.tsx`
- Create: `mobile/src/shared/recovery/userScopedStorage.ts`

- [ ] **Step 1: Test user-scoped recovery**

Recovery key format:

```ts
`af:recovery:${userId}:${formCode}:${draftId ?? 'new'}`
```

Tests must prove logout/user switch cannot read another user's recovery values.

- [ ] **Step 2: Implement throttled recovery writes**

Persist at most once per 500ms and immediately on `visibilitychange` to hidden. Recovery contains schema version, values and timestamp; mismatch prompts the user before discarding.

- [ ] **Step 3: Implement form page**

Load `GET /api/mobile/forms/{code}` and optional draft. Fixed bottom action: 下一步. Top action: 保存草稿. Navigation with dirty values opens a confirm dialog.

- [ ] **Step 4: Implement draft list**

Display form name, updatedAt and filled/total field count. Continue opens form with draftId; delete requires confirmation and removes local recovery.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/forms/FormFillPage.test.tsx src/features/forms/DraftListPage.test.tsx src/shared/recovery
npm run lint
npm run build
Set-Location ..
git add mobile/src/features/forms mobile/src/shared/recovery
git commit -m "功能(移动端): 实现表单填写草稿与离线恢复"
```

## Task 8: Implement Self-Select, Confirm And Submit Success

**Files:**
- Create: `mobile/src/features/forms/SelfSelectPage.tsx`
- Create: `mobile/src/features/forms/SubmitConfirmPage.tsx`
- Create: `mobile/src/features/forms/SubmitSuccessPage.tsx`
- Create: `mobile/src/features/forms/start.api.ts`
- Create: `mobile/src/features/forms/submitFlow.store.ts`
- Create: `mobile/src/features/forms/submit-flow.test.tsx`

- [ ] **Step 1: Define transient flow state**

```ts
type SubmitFlowState = {
  formCode: string | null;
  draftId: number | null;
  values: MobileFormValues;
  selfSelected: Record<string, number[]>;
  reset(): void;
};
```

State is memory-only; form recovery remains the reload fallback.

- [ ] **Step 2: Test conditional navigation**

No self-select nodes goes form → confirm. One or more nodes goes form → self-select → confirm. Success clears state/recovery and links to instance detail.

- [ ] **Step 3: Build self-select page**

Each node displays name and single/multiple rule. Values are keyed by `nodeId`. Required selections block confirmation.

- [ ] **Step 4: Build confirmation and idempotent submit**

```ts
const idempotencyKey = crypto.randomUUID();
await apiRequest<StartResult>('/api/mobile/instances', {
  method: 'POST',
  headers: { 'Idempotency-Key': idempotencyKey },
  body: JSON.stringify({ formCode, data: values, selfSelected, draftId }),
});
```

Keep the same key for a user-triggered retry of the same submission; generate a new key only after the payload changes.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/forms/submit-flow.test.tsx
npm run build
Set-Location ..
git add mobile/src/features/forms
git commit -m "功能(移动端): 完成自选审批人与流程提交闭环"
```

## Task 9: Implement The Three-View Task Center

**Files:**
- Create: `mobile/src/features/tasks/tasks.api.ts`
- Create: `mobile/src/features/tasks/TaskCenterPage.tsx`
- Create: `mobile/src/features/tasks/TaskCard.tsx`
- Create: `mobile/src/features/tasks/TaskFilters.tsx`
- Create: `mobile/src/features/tasks/TaskCenterPage.test.tsx`
- Modify: `mobile/src/shared/api/queryKeys.ts`

- [ ] **Step 1: Define separate task and instance statuses**

```ts
export type TaskListItem = {
  id: number;
  instanceId: number;
  formName: string;
  applicantName: string;
  applicantDepartment?: string;
  nodeName: string;
  taskStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SKIPPED' | 'CC';
  instanceStatus: 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';
  createdAt: string;
};
```

- [ ] **Step 2: Write view and restoration tests**

Test pending/done/process tabs, URL query state, filter restoration after detail, correct badges and empty/error states.

- [ ] **Step 3: Implement paged queries**

Use `useInfiniteQuery` or explicit page query with stable sort `(created_at DESC, id DESC)`. Never fetch an unbounded list.

- [ ] **Step 4: Implement card semantics**

Pending card shows task status. Done card shows the user's action plus separate instance status. Started card shows current node and instance status.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/features/tasks/TaskCenterPage.test.tsx
npm run lint
npm run build
Set-Location ..
git add mobile/src/features/tasks mobile/src/shared/api/queryKeys.ts
git commit -m "功能(移动端): 实现待办发起与已处理三视图"
```

## Task 10: Implement Task Detail, Approve And Reject

**Files:**
- Create: `mobile/src/features/tasks/TaskDetailPage.tsx`
- Create: `mobile/src/features/tasks/ApproveSheet.tsx`
- Create: `mobile/src/features/tasks/RejectSheet.tsx`
- Create: `mobile/src/features/tasks/TaskTimeline.tsx`
- Create: `mobile/src/features/tasks/TaskDetailPage.test.tsx`

- [ ] **Step 1: Write detail/action tests**

Cover readonly form, files, timeline, allowedActions, optional approve comment, required reject comment, reject target selection, loading lock, 409 refetch and success cache invalidation.

- [ ] **Step 2: Render only server-allowed actions**

```tsx
{allowedActions.includes('REJECT') && <Button color="danger">驳回</Button>}
{allowedActions.includes('APPROVE') && <Button color="primary">同意</Button>}
```

Do not show transfer/delegate/add-assignee until their full mobile contracts are implemented.

- [ ] **Step 3: Implement action mutation helper**

```ts
async function runTaskAction(action: 'approve' | 'reject', payload: TaskActionPayload) {
  return apiRequest<void>(`/api/mobile/tasks/${taskId}/${action}`, {
    method: 'POST',
    headers: { 'Idempotency-Key': actionKey.current },
    body: JSON.stringify(payload),
  });
}
```

- [ ] **Step 4: Invalidate exact caches**

After success invalidate bootstrap, pending tasks, done tasks, task detail and instance detail. Navigate back with replace so the completed task cannot reopen from stale history.

- [ ] **Step 5: Handle 409 explicitly**

Close action sheet, show `任务状态已更新`, refetch detail, and render the new readonly status. Do not retry the write automatically.

- [ ] **Step 6: Verify and commit**

```powershell
npm test -- src/features/tasks/TaskDetailPage.test.tsx
npm run build
Set-Location ..
git add mobile/src/features/tasks
git commit -m "功能(移动端): 实现审批详情与同意驳回操作"
```

## Task 11: Implement Process Detail And Withdraw

**Files:**
- Create: `mobile/src/features/processes/processes.api.ts`
- Create: `mobile/src/features/processes/ProcessDetailPage.tsx`
- Create: `mobile/src/features/processes/ProcessSnapshotTimeline.tsx`
- Create: `mobile/src/features/processes/ProcessDetailPage.test.tsx`

- [ ] **Step 1: Write snapshot and withdraw tests**

Assert the UI uses response `processSnapshot`, displays history, renders withdraw only when `canWithdraw`, confirms withdrawal and handles ALREADY_ACTED by refetching.

- [ ] **Step 2: Implement snapshot presentation**

Map node IDs to names from the instance snapshot, then merge history records. Unknown historical node IDs display their ID rather than disappearing.

- [ ] **Step 3: Implement withdrawal**

Use a stable idempotency key, explicit destructive confirmation and cache invalidation for bootstrap/started list/instance detail.

- [ ] **Step 4: Verify and commit**

```powershell
npm test -- src/features/processes/ProcessDetailPage.test.tsx
npm run build
Set-Location ..
git add mobile/src/features/processes
git commit -m "功能(移动端): 实现流程快照进度与撤回"
```

## Task 12: Add Offline Recovery, Error Routing And Telemetry

**Files:**
- Create: `mobile/src/shared/recovery/NetworkStatusProvider.tsx`
- Create: `mobile/src/shared/telemetry/telemetry.ts`
- Create: `mobile/src/shared/telemetry/WebVitalsReporter.tsx`
- Create: `mobile/src/app/GlobalErrorBoundary.tsx`
- Create: `mobile/src/app/ErrorPages.tsx`
- Create: `mobile/src/shared/recovery/recovery.test.tsx`
- Modify: `mobile/src/app/AppProviders.tsx`

- [ ] **Step 1: Test error mapping**

Map 401→refresh/login, 403→forbidden, 404→resource-specific empty, 409→refresh prompt, 422→field/business error, 429→disabled-until retryAfter, 500→traceId page.

- [ ] **Step 2: Add offline behavior**

Display a persistent top OfflineBanner. Disable new writes while offline but preserve form input and queued files. Do not queue approvals for background replay.

- [ ] **Step 3: Add privacy-safe telemetry**

```ts
export type TelemetryEvent = {
  name: string;
  route: string;
  durationMs?: number;
  status?: number;
  code?: string;
  traceId?: string;
};
```

Never include token, password, form values, comments, filenames or signed URLs.

- [ ] **Step 4: Report web vitals**

Record FCP, LCP, CLS and INP through a transport that can be disabled by environment. Development defaults to console debug; production endpoint is configured by `VITE_TELEMETRY_ENDPOINT`.

- [ ] **Step 5: Verify and commit**

```powershell
npm test -- src/shared/recovery src/app
npm run lint
npm run build
Set-Location ..
git add mobile/src/shared/recovery mobile/src/shared/telemetry mobile/src/app
git commit -m "功能(移动端): 增加离线恢复错误路由与性能观测"
```

## Task 13: Add End-To-End Approval And Visual Regression

**Files:**
- Create: `mobile/e2e/helpers/auth.ts`
- Create: `mobile/e2e/helpers/fixtures.ts`
- Create: `mobile/e2e/full-approval-flow.spec.ts`
- Create: `mobile/e2e/draft-recovery.spec.ts`
- Create: `mobile/e2e/permission-errors.spec.ts`
- Create: `mobile/e2e/key-pages.visual.spec.ts`
- Modify: `mobile/playwright.config.ts`

- [ ] **Step 1: Build deterministic backend fixtures**

The helper creates a unique published form/process per test through admin APIs and deletes or namespaces data by run ID. Do not depend on execution order.

- [ ] **Step 2: Implement full approval flow**

```text
admin login → create/publish form and process
bob login mobile → fill → self-select admin → confirm → submit
admin login mobile → pending detail → approve
bob opens process → APPROVED and complete timeline
```

- [ ] **Step 3: Implement recovery/security flows**

- Reload with dirty form and recover values.
- Offline submit remains unsent and retains values.
- Unrelated user receives forbidden instance page.
- Duplicate start/approve using same idempotency key creates one instance/action.

- [ ] **Step 4: Capture all approved key pages**

At 360×800, 375×812, 390×844 and 430×932 capture login, workbench, apps, favorites, form, self-select, confirm, success, pending, task detail, approve sheet, reject sheet, started, process detail, done, profile, drafts, security and offline.

- [ ] **Step 5: Assert visual integrity**

For each screenshot assert `document.documentElement.scrollWidth === viewport width`, fixed actions are visible, no text/button bounding boxes overlap, and primary canvas has non-background pixels.

- [ ] **Step 6: Verify and commit**

```powershell
Set-Location mobile
npm run test:e2e -- full-approval-flow.spec.ts draft-recovery.spec.ts permission-errors.spec.ts key-pages.visual.spec.ts
Set-Location ..
git add mobile/e2e mobile/playwright.config.ts
git commit -m "测试(移动端): 覆盖完整审批闭环与关键页面视觉回归"
```

## Task 14: Enforce Performance And Accessibility Budgets

**Files:**
- Create: `mobile/scripts/check-bundle-budget.mjs`
- Create: `mobile/e2e/performance-accessibility.spec.ts`
- Modify: `mobile/package.json`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add bundle budget script**

Read Vite manifest and gzip entry JS assets. Fail when initial JS exceeds 250 KiB or a single lazy route exceeds 180 KiB.

- [ ] **Step 2: Add browser performance assertions**

On a deterministic local build, assert FCP <1800ms and the page becomes interactive <2500ms using the configured CI machine tolerance. Store measured values as artifacts.

- [ ] **Step 3: Add accessibility checks**

Assert interactive targets are at least 44×44px, labels exist for inputs, dialog focus is trapped, text contrast meets 4.5:1 and 200% text zoom does not hide actions.

- [ ] **Step 4: Add scripts and CI gate**

```json
{
  "scripts": {
    "check:bundle": "node scripts/check-bundle-budget.mjs",
    "check:enterprise": "npm run lint && npm test && npm run build && npm run check:bundle"
  }
}
```

- [ ] **Step 5: Verify and commit**

```powershell
Set-Location mobile
npm run check:enterprise
npm run test:e2e -- performance-accessibility.spec.ts
Set-Location ..
git add mobile/scripts mobile/e2e/performance-accessibility.spec.ts mobile/package.json mobile/package-lock.json .github/workflows/ci.yml
git commit -m "测试(移动端): 增加性能体积与可访问性门禁"
```

## Task 15: Final Enterprise Verification

**Files:**
- Create: `docs/mobile-enterprise-verification.md`
- Modify: `README.md`
- Modify: `codex.md`

- [ ] **Step 1: Run backend verification**

```powershell
Set-Location backend
mvn -B test
```

Expected: all tests pass, including PostgreSQL/Testcontainers integration tests when Docker is available.

- [ ] **Step 2: Run desktop verification**

```powershell
Set-Location ..\frontend
npm run biome:lint
npm test
npm run tsc
npm run build
```

Expected: every command exits 0.

- [ ] **Step 3: Run mobile verification**

```powershell
Set-Location ..\mobile
npm ci
npm run check:enterprise
npm run test:e2e
```

Expected: every command exits 0 at all configured viewports.

- [ ] **Step 4: Run security smoke tests**

Verify public branding without token, admin-only brand mutation, unrelated instance 403, refresh replay rejection, upload type rejection and same-key idempotency replay.

- [ ] **Step 5: Update project references**

README and codex.md must include `mobile/` commands, routes, architecture, brand configuration, known non-goals and enterprise WeChat phase-two boundary. Remove statements that say the mobile client does not exist.

- [ ] **Step 6: Record evidence**

`docs/mobile-enterprise-verification.md` records exact commands, timestamps, test counts, bundle sizes, performance metrics, screenshot viewports and any environment-dependent skipped checks.

- [ ] **Step 7: Commit**

```powershell
Set-Location ..
git add README.md codex.md docs/mobile-enterprise-verification.md
git commit -m "文档: 更新企业级移动端运行与验收说明"
```

## Completion Gate

The enterprise mobile release is complete only when:

- All 14 field types work in fill and readonly modes.
- Nested layout values remain keyed by SchemaNode.id without double lookup.
- Drafts recover across reload but never across users.
- Files upload and read through authorized metadata, never raw storage keys.
- SELF_SELECT, confirm and idempotent start work end to end.
- Pending/started/done views preserve filters and distinguish task/instance status.
- Approve/reject/withdraw handle 409 and invalidate correct caches.
- Process detail renders the instance snapshot.
- Offline and server failures preserve user work.
- All 18 approved key pages pass four-viewport visual regression.
- Bundle, performance, accessibility, lint, type, unit, backend and E2E gates pass.
- Enterprise WeChat remains an adapter boundary and is not partially implemented in phase one.
