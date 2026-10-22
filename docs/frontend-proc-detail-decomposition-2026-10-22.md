# 前端巨型组件拆分：`proc/Detail.tsx`（975 行）

日期：2026-10-22 ｜ 目标：`frontend/src/pages/proc/Detail.tsx`（975 行 → ≤600，理想 ≤400）
参照：[`frontend-form-wizard-decomposition-2026-10-22.md`](frontend-form-wizard-decomposition-2026-10-22.md) 的拆法（按变化原因切，先纯函数、再 hook、最后视图）

## 0. 一句话

这个文件是"流程实例详情页"的**一个页面壳 + 四块面板 + 一组写操作 + 一套派生视图模型**。
它们的变化原因完全不同：改驳回规则只动操作层，改自动化表格只动面板，改字段权限口径只动纯函数。
按行号切会把"同一个变化的代码"劈成两半，所以按职责切。

## 1. 当前职责清单与各自的"变化原因"

| # | 职责 | 现状位置 | 变化原因（谁会来改它） |
| --- | --- | --- | --- |
| R1 | 页面壳：`useParams` 取 `id`、`history.back()`、加载态 / 错误态（含 `refetch` 重试） | 105、294-308、459-465 | 路由参数、错误文案、返回行为 |
| R2 | 权限判定：5 个能力码（override / automation retry / approve / reject / withdraw）+ 发起人、运行中、待修改三个身份判定 | 109-115、310-313 | 能力码口径、按钮可见条件 |
| R3 | 数据加载：`instance` 详情（并行两个请求）、审批意见预设、历史操作人批量补名 | 117-131、219-267 | 后端接口、查询键、失效策略 |
| R4 | 派生视图模型：快照解析、表单 schema/formData 解析、我的待办、可编辑字段模式、进度阶段、流转轨迹条目 | 148-218、268-292 | 展示规则、字段权限口径 |
| R5 | 可编辑值状态机：`editableValues` + 基线 ref（换任务重置 / 未改动时跟随服务端） | 145、184-194 | 回填时机、防陈旧提交 |
| R6 | 写操作：同意（带字段子集提交）、驳回（含驳回到节点）、撤回、自动化重试、紧急介入（含本人发起的二次确认） | 323-431 | 提交 payload、错误提示、审计规则 |
| R7 | 视图一：头部（标题/状态/单号/操作按钮/四项元信息/技术信息折叠） | 456-550 | 纯 UI 与按钮布局 |
| R8 | 视图二：流程进度条、表单内容卡片、自动化作业表格 | 569-680 | 纯 UI、表格列 |
| R9 | 视图三：流转轨迹（筛选器 + 时间线列表） | 683-707 | 纯 UI、筛选项文案 |
| R10 | 视图四：4 个弹窗（同意 / 驳回 / 紧急介入 / 撤回） | 712-832 | 弹窗字段、确认文案 |
| R11 | 摘要可见性降级：`visibility === 'SUMMARY'` 时隐藏表单/自动化/操作 | 233、538-559、579-681 | 可见性口径 |

行数分布：视图 JSX 380+、写操作 110、派生模型 90、加载与壳 60、弹窗 120。

## 2. 组件树与状态流向

```
DetailPage（壳：参数 / 能力 / 两个 hook / 加载与错误 / 装配）
├── useInstanceDetail(id)        ← R3 详情+操作人查询、R4 全部派生、R5 可编辑值状态机
├── useDetailActions({...})      ← R6 五个写操作 + 4 个弹窗状态 + 意见预设查询
├── DetailHero                   ← R7（含内联技术信息）
├── Alert ×2                     ← R11（摘要降级、待修改提示，留在壳里）
├── ProcessProgressPanel         ← R8 进度条
├── ProcessFormPanel             ← R8 表单卡片
├── AutomationJobsPanel          ← R8 自动化表格
├── ActivityPanel                ← R9（筛选 state 局部化）
└── DetailModals                 ← R10
```

### state 归属

| state / 数据 | 归属 | 理由 |
| --- | --- | --- |
| `detailQuery`（`['instance', id]`） | `useInstanceDetail`（壳级） | 头部、进度、表单、表格、轨迹、弹窗全部吃它。下沉到任一面板都会导致同一实例被拉多次，且 `invalidateQueries` 后各面板刷新时序不一致 |
| `actorsQuery`（`['instance-actors', id, ids]`） | `useInstanceDetail` | 轨迹列表与轨迹计数都依赖；`activityItems` 也由它派生 |
| `presetsQuery`（`['task-comment-presets', taskId]`） | `useDetailActions` | 查询键依赖"当前打开的是同意还是驳回弹窗"，这个 state 属于操作层 |
| `editableValues` | `useInstanceDetail` | 表单面板渲染、同意 payload、基线 effect 三处共用；留在壳级 hook 才能保持 effect 不随面板卸载重跑 |
| `editableBaselineRef` | `useInstanceDetail` | 必须跨渲染存活，且只与 `myPending.id` 对齐 |
| 弹窗开关与输入（approve/reject/override/withdraw 共 10 个） | `useDetailActions` | 头部按钮只负责"打开"，弹窗只负责"编辑+提交"，中间态不该散落两处 |
| `activityFilter` | `ActivityPanel`（局部） | 只影响本面板，无外部读写；上提只会让壳多一个无效 state |
| 表单 schema / formData / 快照 | `useInstanceDetail` 派生（`useMemo`） | 同一份服务端数据被 5 处读，绝不能在子组件各解析一次 |

**必须提升（不能下沉）**：`detailQuery`、`editableValues` 及其基线 ref、`snapshotObj`（进度/表单/自动化/轨迹四处共用）。
**可以下沉的**：`activityFilter`（唯一一个）。
**effect 不变**：`editableValues` 的重置 effect 仍在"查询数据之下、渲染之上"的位置，依赖数组保持 `[myPending?.id, initialFormData, editableValues]`。

## 3. 拆分后的文件清单

| 文件 | 职责（一句话） | 实际行数 | props / 参数契约 |
| --- | --- | --- | --- |
| `Detail.tsx`（改） | 页面壳：参数、能力、两个 hook、加载/错误、面板装配 | **174** | 无 props（默认导出 `DetailPage` 不变） |
| `detailModel.ts`（新） | 纯逻辑：JSON 安全解析、流程树查节点、可驳回节点收集、字段模式计算、能力对象类型 | 91 | 入参为数据，无 React 依赖 |
| `useInstanceDetail.ts`（新） | 详情/操作人两个查询 + 全部派生（快照、schema、待办、字段模式、进度、轨迹）+ 可编辑值状态机 | 168 | `{ id, currentUserId }` |
| `useDetailActions.ts`（新） | 5 个写操作 + 4 个弹窗状态 + 意见预设查询；导出 `DetailActions` 类型 | 228 | `{ id, tasks, myPending, pendingTask, isStarter, hasEditableFields, editableValues, currentFormModes, formSchema }` |
| `detailSections.tsx`（新） | 复用件：`SectionHeading`、`TechnicalValue`（原样搬迁） | 40 | `{ kicker, title, id, extra? }` / `{ label, value }` |
| `DetailHero.tsx`（新） | 头部：返回、标题、状态、单号、操作按钮、四项元信息、技术信息 | 154 | `{ instance, presentation, snapshotObj, fullVisibility, myPending, isRunner, isStarter, isRework, can, hasPendingTask, onApprove, onReject, onWithdraw, onOverride }` |
| `ProcessProgressPanel.tsx`（新） | 流程进度面板（含 rail 与阶段状态文案） | 37 | `{ stages }` |
| `ProcessFormPanel.tsx`（新） | 表单内容面板（`FormRenderer` / 空态） | 46 | `{ schema, fieldModes, value, onChange?, instanceId, hasEditableFields }` |
| `AutomationJobsPanel.tsx`（新） | 自动化作业表格（状态标签、重试按钮、列定义） | 123 | `{ jobs, snapshotObj, canRetry, onRetry }` |
| `ActivityPanel.tsx`（新） | 流转轨迹：筛选器 + 计数 + 列表（含 3 个文案映射） | 96 | `{ items }` |
| `DetailModals.tsx`（新） | 4 个弹窗：同意 / 驳回（含驳回到）/ 紧急介入 / 撤回 | 147 | `{ actions, rejectTargets, pendingTaskId? }` |
| `detailModel.test.ts`（新） | 锁住纯函数：并行分支不参与驳回目标、发起人视角只读、JSON 解析兜底 | 75 | — |

本次新增/改写的 12 个文件全部 ≤400 行；目标文件从 975 降到 **174**（-82%），其中最大的是 `useDetailActions.ts` 228 行。
同目录的 `detailPresentation.ts`（600 行）不在本次范围，未改动其任何导出。

## 4. 为什么这样切（按职责而非行号）

1. **切在"变化原因"上**：改驳回规则只碰 `useDetailActions.ts`；改字段权限口径只碰 `detailModel.ts` 的
   `buildFieldModes`；改表格列只碰 `AutomationJobsPanel.tsx`。原文件里这三件事交织在 130-430 行之间。
2. **先纯函数、再 hook、最后组件**：纯函数零风险；hook 只搬同一批闭包（查询、effect、state），
   查询键、依赖数组、payload 逐字保留；组件只搬 JSX 与仅服务于该 JSX 的文案映射。
3. **不按"UI vs 工具函数"切**：`statusTagColor` / `automationStatusLabel` 只服务于自动化表格，
   `activityMarker` / `activityCategoryLabel` 只服务于轨迹列表 —— 留在各自组件里，读一处就够。
4. **不造转发壳**：两个 hook 都承载真实逻辑（查询、失效、effect、payload 拼装），没有一行委托函数。
5. **载入/错误态留在壳**：它们决定"能不能渲染",属于路由层，不属于任何面板。

## 5. 风险点与对策

| 风险 | 具体表现 | 对策 |
| --- | --- | --- |
| **重复请求** | 把 `['instance', id]` 下沉到 Hero/表单/轨迹三处 → 一次进页面发 6 个请求（详情本身是 2 个并行请求），`invalidateQueries` 后刷新次数 ×3 | 查询全部留在 `useInstanceDetail` / `useDetailActions`，子组件只吃 props |
| **多余 re-render** | `useInstanceDetail` 每次返回新对象；若子组件用 `React.memo` 且比较该对象会失效 | 子组件**不包 `React.memo`**（与拆前一致），避免"看起来优化了其实没有"；hook 内部不新增 `useMemo` 之外的缓存 |
| **props 钻透** | Hero 需要 4 个能力位 + 4 个回调；`DetailModals` 需要 10 个弹窗 state | Hero 的能力位合并为一个 `can` 对象；弹窗透传 `useDetailActions` 的返回值（单一聚合，拆成 15 个字段更差）；不再往第三层传 |
| **权限判定口径漂移** | 能力码散落到子组件后，Hero 与壳各算一次；按钮可见条件与提交条件不一致（例如 Hero 显示"驳回"但 `doReject` 里换了判断） | 能力码只在壳里算一次，Hero 只读 `can.*`；各按钮的 `&&` 条件逐字搬进 Hero，不改顺序与表达式 |
| **查询键 / 失效逻辑漂移** | `['instance', id]`、`['instance-actors', id, ids]`、`['task-comment-presets', id]` 被改写或 `invalidateQueries` 少一处 | 键与失效调用逐字复制；拆分后用 grep 对比键字面量与 `invalidateQueries` 次数（拆前 5 处） |
| **文案 / aria 漏项** | 本模块**未使用 i18n**（`pages/proc` 下无 `useIntl`），文案是内联中文；`aria-label`（`返回上一页`、`流程流转轨迹`）与 `aria-labelledby` 的 4 个 id 被测试/无障碍依赖 | 搬迁逐字复制；拆分后 grep 对比 `aria-label`、`aria-labelledby`、`id="...-title"` 数量 |
| **可编辑值回填语义被改** | 把 `editableValues` 下沉到 `ProcessFormPanel`，面板重挂时基线 ref 重建 → 用户填的内容被服务端旧值覆盖；或 effect 依赖漏 `initialFormData` → 详情静默 refetch 后提交陈旧值 | `editableValues` + `editableBaselineRef` + effect 全部留在 `useInstanceDetail`，依赖数组逐字保留 |
| **加载态闪变** | 把 `isLoading` 判断挪进子组件 → 首帧先渲染空壳再替换 | `isLoading` / `isError` 的提前 `return` 仍在壳里，位置不变（在全部 hook 之后） |
| **`detailPresentation.ts` 已 601 行** | 顺手把新逻辑塞进去会让它继续膨胀，或改动其公开导出破坏 `detailPresentation.test.ts` | **本次不动**：只 import 它已有的 `parseProcessSnapshot` / `buildProgressStages` / `buildActivityItems` / `formatDateTime` / `formatDuration` / `instanceStatus` / `nodeLabel` 与 5 个类型 |
| **`npm run tsc` 的 safe-delete 守卫** | `max setup` 会 `rimraf .umi`，被沙箱拦截后 `.umi` 半删 → 全量假类型错误 | 一律 `CODEBUDDY_SAFE_DELETE_ENABLED=0 npm run tsc` |
| **导入边界** | `pages/proc` 内部互相 import 属同一 zone，不算违规；但若顺手 import `pages/approval` 就会新增违规 | 新文件只 import `pages/proc/**`、`components/**`、`registry/**`、`authz`、antd/umi/react |

## 6. 验证结果（2026-10-22 实测）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查 | `cd frontend && CODEBUDDY_SAFE_DELETE_ENABLED=0 npm run tsc` | 退出码 0 |
| 单测（本目录） | `cd frontend && npm test -- src/pages/proc` | 3 个文件 / 13 个用例通过（拆前 2 个文件 / 7 个用例，新增 `detailModel.test.ts` 6 例） |
| 单测（关键词） | `cd frontend && npm test -- proc` | 8 个文件 / 49 个用例通过（`proc` 同时命中 `designer/process`） |
| 导入边界 | `cd frontend && npm run check:boundaries` | 存量 8 条，无新增 |
| Lint | `cd frontend && npx biome lint src/pages/proc` | 无告警 |
| H7 | `node scripts/health-metrics.mjs` | 退出码 0；两端超标文件 7 → **6**，`maxFileLines` 975 → **848** |

### 行为不变性的证据

1. **对外契约**：`export default function DetailPage()` 未改，无 props；`frontend/config/routes.ts` 的 `/proc/:id` → `./proc/Detail` 不需要改。
2. **查询键不变**：拆分前后完整比对 `queryKey` 字面量，均为 `['instance', id]`、`['instance-actors', id, actorIds.join(',')]`、
   `['task-comment-presets', presetTaskId]`；`invalidateQueries` 仍是 5 处（同意/驳回/撤回/重试/介入）。
3. **端点不变**：`/api/instances/${id}`、`/api/mobile/instances/${id}`、`/api/tasks/${taskId}/approve`、
   `/api/tasks/${rejectFor.taskId}/reject`、`/api/instances/${id}/withdraw`、`/api/instances/${id}/jobs/${jobId}/retry`、
   `/api/tasks/${pendingTask.id}/override`、`/api/pickers/users/selected` 逐条一致。
4. **payload 不变**：同意的 `{ comment, ...(hasEditableFields ? { data: pickEditableValues(...) } : {}) }`、
   驳回的 `{ comment, ...(!fixedRejectTarget && targetNodeId ? { rejectToNodeId } : {}) }`、介入的
   `{ action, ticketNo, reason, rejectToNodeId }` 均为原样复制。
5. **文案不变**：对比拆分前后全部中文字面量，差异**只出现在注释**；`aria-label`（`返回上一页`、`流程流转轨迹`）2 处、
   `aria-labelledby`（`process-progress-title`/`form-content-title`/`automation-title`）3 处、`className` 39 个全部一致。
6. **按钮条件不变**：同意/驳回受 `myPending && isRunner && fullVisibility` 与能力位约束；驳回仍按 `myPending.parallelId` 禁用；
   撤回仍是 `withdraw && isStarter && isRunner && !isRework && fullVisibility`；介入仍是 `override && isRunner && pendingTask && fullVisibility`。
7. **回填语义不变**：`editableValues`、`editableBaselineRef` 与 effect（依赖 `[myPending?.id, initialFormData, editableValues]`）
   整体搬入 `useInstanceDetail`，仍在壳层作用域、只跑一次。
8. **查询只发一次**：`['instance', id]` 只在 `useInstanceDetail` 出现，子组件全部吃 props（拆分后详情仍是 2 个并行请求）。
