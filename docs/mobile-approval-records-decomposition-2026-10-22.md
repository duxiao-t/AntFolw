# 移动端巨型组件拆分：`tasks/ApprovalRecords.tsx`（848 行）

日期：2026-10-22 ｜ 目标：`mobile/src/features/tasks/ApprovalRecords.tsx`（848 行 → ≤600，理想 ≤400）
参照：[`frontend-proc-detail-decomposition-2026-10-22.md`](frontend-proc-detail-decomposition-2026-10-22.md) 的拆法（先纯函数、再视图，按变化原因切）
范围边界：只动 `mobile/src/features/tasks/**`（测试文件一字不改），无新增依赖，不碰 `mobile/src/shared/**`。

## 0. 先纠正一处前提

任务描述里列的是"列表（我发起的/待我审批/已完成）、筛选与搜索、分页/下拉加载"——**这些不在本文件**。
那些职责在 `TaskCenterPage.tsx`（5.8 KB）+ `tasks.api.ts`（`fetchTaskCenterItems` / `TaskCenterFilters`）里。

`ApprovalRecords.tsx` 实际是**流程实例的审批流转时间线渲染器**，只有两个导出：
`ApprovalRecords`（默认 + 具名）与 `approvalSummaryLabel`。两个调用方（`TaskDetailPage.tsx:132`、
`ProcessDetailPage.tsx:63`）都只传 4 个 props，不涉及路由、筛选、分页、请求。

所以本次拆分按**它真实的职责**切，不虚构"筛选/分页"层。拆分目标与 H7 判据不变。

## 1. 当前职责清单与各自的"变化原因"

| # | 职责 | 现状行号 | 变化原因（谁会来改它） |
| --- | --- | --- | --- |
| R1 | 时间线装配：记录排序、并行分支分组、轮次分隔、条件分支去重展示 | 326-414 | 流转展示口径 |
| R2 | 自动化记录派生：从 `history` 的 DELAY/TRIGGER/AUTO_PASS 事件合成"系统"记录，并按节点配对起止 | 416-486 | 后端历史事件种类 |
| R3 | 抄送合并：同节点同分支同轮次的 CC 记录合并操作人与部门 | 488-520 | 抄送展示规则 |
| R4 | 记录增强：补齐 `nodeType` / `parallelId` / `branchId` / 条件路径 | 522-540 | 快照字段口径 |
| R5 | 流程树导航：快照解析、节点查找、路径查找、并行上下文、分支列表、节点命名 | 621-727 | 快照结构 |
| R6 | 条件文案：条件分支标签、条件表达式转中文（含字段选项/后缀） | 542-619 | 条件渲染文案 |
| R7 | 状态与标签映射：视觉档位、状态文案、操作标签、默认意见、时间格式化、汇总文案 | 227-231、729-846 | 纯文案/样式口径 |
| R8 | 卡片视图：节点名/操作人/来源人/状态/意见/部门工号/时间/操作标签 | 155-225 | 卡片 UI |
| R9 | 时间线轨道标记（图标按视觉档位） | 123-153 | 图标与 class |
| R10 | 详情半屏弹层：节点/人/部门/接收完成时间/意见 | 233-324 | 弹层字段与文案 |
| R11 | 列表壳：空态、`selected` state、块分发（轮次/条件/并行/单条） | 33-121 | 页面结构 |

行数分布：纯逻辑（R1-R6）约 400 行、文案映射 130 行、视图 260 行、壳 90 行。
R1-R6 与 React 完全无关（零 hooks、零 JSX），这是拆分的第一个自然断层。

## 2. 组件/纯函数切分方案与理由

```
ApprovalRecords.tsx（壳：空态 / selected state / 块分发 / 弹层挂载）
├── approvalRecordsTimeline.ts   ← R1-R4：buildTimeline + 类型 + 自动化派生 + CC 合并
│   └── approvalRecordsFlow.ts   ← R5-R6：快照导航 + 条件文案
│       └── approvalRecordLabels.ts ← R7 的 automationComment（被 R2 复用）
├── ApprovalRecordCard.tsx       ← R8-R9：RecordItem / RecordCard
├── ApprovalRecordSheet.tsx      ← R10：RecordSheet
└── approvalRecordLabels.ts      ← R7：状态/标签/时间/汇总文案
```

**切法理由**

1. **纯逻辑先出**（`approvalRecordsTimeline.ts` + `approvalRecordsFlow.ts`）：合计约 450 行、零 React 依赖。
   它们的变化原因（流转展示口径、快照结构、条件文案）与 UI 无关，混在组件文件里改一行要翻 800 行。
   拆开后 `buildTimeline` 可被单测直接调用（测试文件本次不改，但未来能加）。
2. **树导航与时间线装配分开**：`findNode` / `findPath` / `parallelContext` / `conditionContexts` 是
   通用快照工具（`conditionContexts` 还依赖 `formatCondition`），`buildTimeline` 是业务装配。
   合成一个文件会到 450 行以上，逼近 400 行纪律线，且两者改动频率不同。
3. **文案映射独立**（`approvalRecordLabels.ts`）：11 个纯函数被卡片、弹层、时间线、两个调用方
   （`approvalSummaryLabel`）四处消费。它们是"看一眼就能改"的映射表，独立后卡片/弹层只 import 不复制。
4. **卡片与弹层各自成文件**：两者只有 `record` 一个共享输入，没有任何共享 state；卡片 100 行、
   弹层 90 行，合成一个"视图文件"会让以后加一个字段时两边都要翻。
5. **壳只留 90-160 行**：`selected` state 必须留在壳里——卡片点击、并行分支卡片点击、弹层关闭三处共用，
   下沉到任一卡片会让每个卡片各持有一份"打开的是谁"，弹层也无法单例渲染。
6. **不造转发壳**：`approvalSummaryLabel` 用一行 `export { approvalSummaryLabel }` 从标签模块透出，
   因为测试与两个调用方都从 `./ApprovalRecords` 具名导入；这只是保持对外契约，不是新建抽象层。

### state 归属

| state | 归属 | 理由 |
| --- | --- | --- |
| `selected`（当前打开详情的记录） | `ApprovalRecords.tsx` | 卡片（单条 + 并行分支内）点击写入、弹层关闭清空；上提到此处才能保证同时只开一个弹层 |
| `blocks`（`useMemo(buildTimeline(...))`） | `ApprovalRecords.tsx` | 依赖 `[history, processSnapshot, records, schema]` 原样保留，位置不变（组件内、空态判断之前） |

**没有新增 state、没有新增 effect、没有新增请求**——本文件拆前拆后都是纯展示组件。

## 3. 新文件清单

| 路径 | 职责 | 行数（实测） | props / 参数契约 |
| --- | --- | --- | --- |
| `tasks/ApprovalRecords.tsx`（改） | 壳：空态、`selected`、`useMemo(buildTimeline)`、块分发、弹层挂载 | 111 | `{ records, processSnapshot?, schema?, history? }`（不变） |
| `tasks/approvalRecordsFlow.ts`（新） | 快照解析、节点/路径查找、并行上下文与分支、节点命名、条件分支文案 | 197 | 入参为数据，无 React 依赖 |
| `tasks/approvalRecordsTimeline.ts`（新） | `TimelineRecord`/`TimelineBlock` 类型、`buildTimeline`、自动化历史派生、CC 合并、记录增强 | 245 | `(records, processSnapshot, schema, history)` |
| `tasks/approvalRecordLabels.ts`（新） | 汇总文案、视觉档位、状态/操作标签、默认意见、时间格式化 | 131 | 入参为 `ApprovalRecord` / `ApprovalSummary` / 字符串 |
| `tasks/ApprovalRecordCard.tsx`（新） | 单条记录 `<li>`（轨道标记 + 卡片）与可复用卡片（并行分支用 `compact`） | 123 | `RecordItem { record, onSelect }`；`RecordCard { record, compact?, onSelect }` |
| `tasks/ApprovalRecordSheet.tsx`（新） | 审批记录详情半屏弹层 | 99 | `{ record, onClose }` |

对外导出保持不变：`ApprovalRecords`（具名 + 默认）、`approvalSummaryLabel`。
每个新文件 ≤400 行；目标文件目标 ≤200 行。

## 4. 风险点与对策

| 风险 | 具体表现 | 对策 |
| --- | --- | --- |
| **重复请求** | 本文件不发起任何请求（props 由页面传入），拆错也不会新增请求；反之若把 `approvalSummaryLabel` 的调用方误改成直接 import 新路径，会破坏 3 处导入 | 保持 `ApprovalRecords.tsx` 的具名导出，调用方与测试零改动 |
| **滚动加载边界** | 本文件无分页/无限滚动；真正的边界是"空态判定"——`blocks.length === 0` 必须在 `buildTimeline` 之后、且弹层不渲染 | 空态 `return` 位置逐字保留在 `useMemo` 之后 |
| **筛选状态与 URL 同步** | 本文件无筛选、无 URL 参数（筛选在 `TaskCenterPage`） | 不引入任何筛选 state；不新增 `useSearchParams` |
| **i18n key** | 项目未接 i18n，全部中文硬编码；`aria-label="关闭审批记录详情"` / `"审批记录详情"`、`aria-modal`、`role="dialog"` 被测试与无障碍依赖 | 全部字面量逐字搬迁，拆完 grep 对比 `aria-label` / `role="dialog"` 数量 |
| **`mergeCc` 原地改对象** | `mergeCc` 会 `sort` 入参数组并改写 `previous`（合并操作人/部门/状态），依赖调用方先做浅拷贝（`[...records, ...automationRecords(...)]`） | 该调用表达式逐字保留；不改成"看起来更干净"的不可变写法，避免合并语义变化 |
| **排序稳定性** | 三处 `Date.parse(...)` 升序排序（records、history、mergeCc 内部）决定并列时间的展示顺序 | 排序表达式与调用顺序逐字复制 |
| **轮次/条件块的 key** | `round-${roundNo}-${blockIndex}`、`condition-${key}-${blockIndex}`、`parallel-${parallelId}-${record.id}` 参与 React 复用；改 key 会导致动画/焦点抖动 | key 模板逐字保留 |
| **class 名与视觉档位** | `visualKind` 同时驱动卡片 class、`<li>` class、轨道图标、标签 class；测试断言 `approval-record-card--rejected` / `--current` 数量为 1 | 映射函数整体搬到标签模块，不在视图里二次判断 |
| **循环依赖** | 时间线模块需要 `automationComment`（标签模块），标签模块不反过来依赖时间线 | 依赖方向单向：`flow ← timeline ← 壳`、`labels ← {timeline, card, sheet, 壳}`；标签模块只依赖 `tasks.api` 类型 |
| **测试文件不可动** | `ApprovalRecords.test.tsx` 从 `./ApprovalRecords` 导入两个具名符号，覆盖并行分组、轮次、条件路径、CC 合并、自动化、弹层 | 拆分后原样跑该测试；12 个用例必须全绿 |

## 5. 验证计划

1. `cd mobile && npx tsc -b --noEmit`（每抽一个文件跑一次）
2. `cd mobile && npm test -- ApprovalRecords`（期望 12 passed，与拆前同数）
3. `cd mobile && npm run lint`
4. `cd .. && node scripts/health-metrics.mjs`（退出码 0，`frontendOverLines` 4 → 3）

## 6. 实测结果（2026-10-22）

| 文件 | 实测行数 | 职责 |
| --- | --- | --- |
| `tasks/ApprovalRecords.tsx` | **111** | 壳：空态、`selected`、`useMemo(buildTimeline)`、块分发、弹层挂载、`export { approvalSummaryLabel }` |
| `tasks/approvalRecordsFlow.ts` | 197 | 快照解析、节点/路径查找、并行上下文、节点命名、条件分支文案 |
| `tasks/approvalRecordsTimeline.ts` | 245 | `TimelineRecord`/`TimelineBlock` 类型、`buildTimeline`、自动化历史派生、CC 合并、记录增强 |
| `tasks/approvalRecordLabels.ts` | 130 | 汇总文案、视觉档位、状态/操作标签、默认意见、时间格式化 |
| `tasks/ApprovalRecordCard.tsx` | 123 | `RecordItem`（`<li>` + 轨道标记）与 `RecordCard`（并行分支 `compact`） |
| `tasks/ApprovalRecordSheet.tsx` | 99 | 审批记录详情半屏弹层 |

目标文件 848 → **111**（-87%），6 个文件全部 ≤400 行。原文件 588 条去重非空行逐行比对：
除 26 处 `function X(` → `export function X(` 的可见性调整外，**0 行丢失**。

### 验证输出

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查 | `cd mobile && npx tsc -b --noEmit` | 退出码 0（每抽一个文件各跑一次，5 次全绿） |
| 单测（本组件） | `cd mobile && npm test -- ApprovalRecords` | 1 文件 / **12 用例**通过（与拆前同数） |
| 单测（同目录） | `cd mobile && npm test -- src/features/tasks` | 5 文件 / 36 用例通过 |
| 单测（调用方） | `cd mobile && npm test -- src/features/processes` | 1 文件 / 10 用例通过 |
| 单测（全量） | `cd mobile && npm test` | 48 文件 / **449 用例**全部通过（无回归） |
| Lint | `cd mobile && npm run lint` | 退出码 0；新拆 6 文件单独 lint「Checked 6 files，无告警」；存量 10 warning 在 `shared/api/http.test.ts` 等未改动文件 |
| H7 | `node scripts/health-metrics.mjs` | 退出码 0；`frontendOverLines` **4 → 3**（基线 8），`maxFileLines` 1250 → 1236 |

### 行为不变性的证据

1. **导出契约**：`export function ApprovalRecords` + `export default ApprovalRecords` 未改；
   `approvalSummaryLabel` 由 `export { approvalSummaryLabel } from './approvalRecordLabels'` 透出，
   `ApprovalRecords.test.tsx:4`、`TaskDetailPage.tsx:17`、`ProcessDetailPage.tsx:11` 三处导入**一字未改**。
2. **文案零漂移**：拆前文件 78 个中文片段在新文件中**全部命中**（缺失 0）；新增 20 个片段全部是本轮中文注释。
3. **结构标记零漂移**：`aria-label` 2 处、`aria-hidden` 3 处、`role="dialog"` 1 处、
   `approval-record-card--*` 3 处、`approval-records__*` 17 处，拆前拆后计数一致。
4. **state 与派生不变**：`selected` 仍在壳层；`useMemo` 依赖数组 `[history, processSnapshot, records, schema]` 逐字保留，
   空态 `blocks.length === 0` 的提前返回位置不变。
5. **无新增请求**：拆分前后都不含 `fetch` / `apiRequest` / `useQuery`（全仓 grep 该 6 文件为 0 处）。
6. **测试文件未动**：`ApprovalRecords.test.tsx` 的 `git status` 无变更，12 个用例覆盖并行分组、两列/堆叠布局、
   轮次分隔、嵌套条件路径、CC 合并、自动化历史、作废态、弹层打开等，全部通过。

### 遗留风险

| 风险 | 说明 |
| --- | --- |
| 记录点击后 tab 顺序 | `RecordCard` 在并行分支里是 `<button>`，拆分未改变 DOM 层级与顺序，tab 顺序同拆前；若将来给并行卡片加 `tabIndex` 需注意 |
| `mergeCc` 的原地写 | 合并逻辑仍会改写入参数组元素（依赖调用方浅拷贝 `[...records, ...automationRecords(...)]`）。已在注释里标注，但若未来有人在别处复用 `mergeCc` 并传入调用方数组，会污染 props |
| 纯函数仍无单测 | 本次纪律要求“不改测试文件”，`approvalRecordsFlow.ts` / `approvalRecordsTimeline.ts` 的可测性提升了但未补测试，后续可加 `approvalRecordsTimeline.test.ts` 直接断言 `buildTimeline` |
| 文件数量 | 单目录从 1 个 848 行文件变为 6 个文件，import 图变长；依赖方向已固定为单向（api 类型 ← labels ← timeline ← card/sheet ← 壳，flow 被 timeline 依赖），无环 |
