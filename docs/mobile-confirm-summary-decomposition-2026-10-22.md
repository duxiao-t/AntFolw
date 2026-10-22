# 移动端巨型组件拆分：`forms/components/ConfirmSummaryList.tsx`（739 行）

日期：2026-10-22 ｜ 目标：`mobile/src/features/forms/components/ConfirmSummaryList.tsx`（739 行 → ≤600，理想 ≤400）
参照：[`mobile-approval-records-decomposition-2026-10-22.md`](mobile-approval-records-decomposition-2026-10-22.md)（848 → 111）、
[`mobile-file-upload-field-decomposition-2026-10-22.md`](mobile-file-upload-field-decomposition-2026-10-22.md)（845 → 132）的拆法：
先纯函数、再视图、最后壳，按"变化原因"切。
范围边界：只动 `mobile/src/features/forms/components/**`（可新增文件，测试文件一字不改）+ 本文件；
不新增依赖，不碰 `mobile/src/shared/**`、`forms/fields/**`、`forms/{tasks,profile}`、`frontend/**`、`backend/**`、`scripts/**`。

## 1. 现状职责清单与"变化原因"

本文件是**已填内容的只读摘要渲染器**（确认页 / 直提详情 / 流程详情 / 任务详情四处共用），
对外只有 3 个出口：`ConfirmSummaryList`（具名 + 默认）、`summarizeSchemaRows`、类型 `SummaryRow` / `ConfirmSummaryListProps`。

| # | 职责 | 现状行号 | 变化原因（谁会来改它） |
| --- | --- | --- | --- |
| R1 | 条目收集：可见性过滤（`visibleNodeIds`）、`hidden` 模式剔除、`description` 剔除、布局容器下钻、`includeEditable` 过滤 | 93-131 | 确认页展示口径（"哪些字段该出现"） |
| R2 | 部门/人员附加文件的去重：未挂到任何可见字段的 `extraFiles` 收成「其他附件」一行 | 65-71、518-583 | 直提附件的归属规则 |
| R3 | 单字段摘要文案 `summaryText`：人员/部门/清单/明细表/矩阵/定位/媒体/选项/文本/兜底注册表 | 419-472 | 文案口径（最常被改的一块） |
| R4 | 字段值解析与格式化：`mediaFiles`/`mediaFile`、`optionLabels`、`locationValue`、`matrixHasContent`、`tableRows`/`tableRowIds`、`pickerIds` | 474-643 | 后端值结构兼容 |
| R5 | 紧凑文本规则：`compactText`（32 字截断）、`compactLabels`（3 项 + "等 N 项"）、`fullText`、`textNeedsDetail`、`safeJson` | 694-737 | 排版口径 |
| R6 | 人员/部门远端补名：`usePickerUsers` / `usePickerDepartments`（按 id 并发拉取、失败回落、卸载保护） | 645-687 | 选择器接口与降级策略 |
| R7 | 行视图：`SummaryItemFrame`（`confirm-row` + 展开按钮 + chevron + `aria-expanded/controls`） | 200-239 | 行交互与无障碍 |
| R8 | 人员行 / 部门行（展开后的人名-部门-工号列表、部门名称/编号定义列表） | 157-198 | 人员卡片样式 |
| R9 | 详情分发 `detailFor`：按字段类型决定"能否展开"与展开后渲染什么 | 241-288 | 字段类型覆盖（新字段类型必改这里） |
| R10 | 各类型详情视图：清单项、明细表行（可再嵌套本组件）、矩阵表格、定位、选项标签云 | 290-417 | 详情 UI |
| R11 | 壳：空态、`nested` class、行列表映射 | 52-81 | 页面结构 |

行数分布：纯逻辑（R1-R5）约 340 行、请求 hook（R6）约 45 行、视图（R7-R10）约 250 行、壳约 40 行。
R1-R5 零 hooks、零 JSX，是第一个自然断层；R6 是有状态无 JSX；R7-R10 是视图。

## 2. 组件 / 纯函数切分方案与理由

```
ConfirmSummaryList.tsx（壳：空态 / nested class / 行映射 / SummaryItem 分发 / 递归 renderNested）
├── confirmSummaryEntries.ts  ← R1/R2：条目收集、可见性、其他附件归属
│   └── confirmSummaryFormat.ts ← R3/R4/R5：摘要文案、值解析、紧凑文本规则
├── ConfirmSummaryViews.tsx   ← R7/R8：行壳、人员行、部门行
│   └── usePickerIdentities.ts ← R6：人员/部门补名
└── ConfirmSummaryDetails.tsx ← R9/R10：detailFor 分发 + 清单/明细表/矩阵/定位/选项标签
    └── ConfirmSummaryViews.tsx / confirmSummaryFormat.ts
```

**切法理由**

1. **格式化纯函数独立**（`confirmSummaryFormat.ts`）：`compactText` / `compactLabels` / `optionLabels` /
   `mediaFiles` / `locationValue` / `matrixHasContent` 是**结果口径**，被摘要文案、详情视图、条目归属三处共用。
   它们的变化原因是"显示成什么样"，与"渲染成什么结构"无关；留在组件文件里，任何文案微调都要翻 700 行。
2. **条目收集独立**（`confirmSummaryEntries.ts`）：「哪些字段出现」与「字段长什么样」是两个不同的问题。
   R1 依赖 `visibleNodeIds` + `fieldModes`（显示逻辑），R2 的 `schemaHasFileId` 还要递归下钻明细表行和清单图片
   （归属逻辑）——这两块加起来 90 行，且都被 `summarizeSchemaRows`（对外导出）复用。
3. **补名 hook 独立**（`usePickerIdentities.ts`）：全文件唯一的网络请求与唯一的两处 `useEffect`。
   抽走后，"确认页会不会额外发请求"可以一眼确认（只剩这一个文件）。`ids` 用 `useMemo` 派生后作为 effect 依赖的写法逐字保留。
4. **视图按"行"与"详情"分开**：`SummaryItemFrame` 决定的是**行壳**（能否展开、`aria-*`、chevron），
   详情决定的是**展开后画什么**。两者变化频率不同（改行交互 vs 加字段类型），且详情要能被明细表行递归复用。
5. **明细表的递归不引入环**：`TableSummaryDetail` 展开行时原本直接渲染 `<ConfirmSummaryList ... nested />`。
   若详情文件 import 壳文件、壳文件又 import 详情文件，就成环。因此详情函数收一个 `renderNested` 回调
   （由壳文件以模块级函数传入，身份稳定），由壳文件自己渲染递归行——**DOM 结构与参数完全不变**。
6. **不发散**：不把 5 个纯函数各自拆文件（会碎成 10 个文件），也不新增抽象（无 context、无 memo 包装、无渲染器注册表）。

### state / hook 归属

| state / hook | 归属 | 理由 |
| --- | --- | --- |
| `expanded`（单行展开） | `ConfirmSummaryViews.tsx` 的 `SummaryItemFrame` | 原实现即在行内，每个字段独立展开 |
| `expandedRows`（明细表行展开） | `ConfirmSummaryDetails.tsx` 的 `TableSummaryDetail` | 同上，原位置 |
| `users` / `departments` 缓存 | `usePickerIdentities.ts` | 与原实现同一 effect 结构 |
| `useMemo(summaryEntries)` / `useMemo(unlinkedFiles)` | 壳 | 依赖数组逐字保留 `[fieldModes, includeEditable, schema, values]` / `[extraFiles, schema, values]` |

**没有新增 state、没有新增 effect、没有新增请求**；`useMemo` 数量（组件内 2 个）与行内 1 个、明细表 1 个、人员/部门各 2 个全部保持。

## 3. 新文件清单

| 路径 | 职责 | 行数估算 | props / 参数契约 |
| --- | --- | --- | --- |
| `components/ConfirmSummaryList.tsx`（改） | 壳：空态、`nested`、条目装配、`SummaryItem` 分发、递归 `renderNested` | ~120 | `ConfirmSummaryListProps`（不变） |
| `components/confirmSummaryFormat.ts`（新） | 紧凑文本、标签合并、选项标签、媒体解析、定位/矩阵/明细表值解析、类型判定 | ~235 | 入参 `unknown` / `MobileSchemaNode`，零 React |
| `components/confirmSummaryEntries.ts`（新） | `SummaryRow`/`SummaryEntry` 类型、`summaryEntries`、`summarizeSchemaRows`、其他附件归属 | ~130 | `(schema, values, fieldModes, includeEditable)` |
| `components/usePickerIdentities.ts`（新） | `usePickerUsers` / `usePickerDepartments`、回落文案、工号元信息 | ~75 | `(endpoint, ids)` → 解析后的实体数组 |
| `components/ConfirmSummaryViews.tsx`（新） | `SummaryItemFrame`、`UserSummaryItem`、`DepartmentSummaryItem` | ~125 | `{ id, label, value, detail }` / `{ entry }` |
| `components/ConfirmSummaryDetails.tsx`（新） | `renderSummaryDetail`、清单/明细表/矩阵/定位/选项标签详情 | ~245 | `(node, value, extraFiles?, fieldModes?, renderNested)` |

对外导出保持不变：`ConfirmSummaryList`（具名 + 默认）、`summarizeSchemaRows`、`SummaryRow`、`ConfirmSummaryListProps`。
四个调用方（`SubmitConfirmPage.tsx:8`、`DirectSubmissionDetailPage.tsx:5`、`ProcessDetailPage.tsx:9`、`TaskDetailPage.tsx:8`）
与测试 `formFlowComponents.test.tsx:5` 的导入路径与符号**一字不改**。每个新文件 ≤400 行；目标文件目标 ≤200 行。

## 4. 风险点与对策

| 风险 | 具体表现 | 对策 |
| --- | --- | --- |
| **格式化口径** | 空值统一写「未填写」；清单 `未完成` → 「未填写」且 `1 项` → `1项`（去空格）；明细表 `N条明细`（无空格）；矩阵 `N 行 × M 列`（有空格）；媒体 `N张图片/N个视频/N段录音/N个附件`；人员 `用户#id`；部门 `部门 #id`（**有空格**） | 这些字面量与正则 `/(\d+) 项/g` 逐字搬迁，不做"统一空格"式清理；拆完用去重字符串比对 |
| **长文本/截断** | `compactText` 按 `Array.from` 计码点、32 字截断加 `…`；`compactLabels` 超过 3 项加 ` 等N项`；`textNeedsDetail` 只在"不是未填写且含换行或超 32 字"时展开 | 三个函数逐字复制；测试 `renders compact summary rows`、`expands long text` 与之直接相关 |
| **附件缩略图** | `ReadonlyMediaList` 只在**展开后**渲染（测试断言折叠时 `fetch` 未被调用）。`detailFor` 里 `extraFiles` 优先于 `node.type` 判断 | `detailFor` 的分支顺序逐字保留；`expanded ? <div id=...>{detail}</div> : null` 结构不变（detail 是元素而非惰性函数，但只在展开时挂载，与拆前一致） |
| **递归渲染** | 明细表行展开 → 嵌套 `ConfirmSummaryList`（`nested` class、自带状态） | 用 `renderNested` 回调由壳文件渲染，避免循环 import；回调是模块级函数（身份稳定），不触发额外 remount |
| **i18n key** | 项目未接 i18n，全部中文硬编码；`aria-expanded`、`aria-controls="summary-detail-${id}"`、`data-testid="summary-${id}"` 被测试依赖 | 全部字面量逐字搬迁；`SummaryItemFrame` 不改 DOM 层级（可展开时是 `<button class="confirm-row confirm-row--expandable">`，否则是 `<div class="confirm-row">`） |
| **渲染性能** | 每行一个 `SummaryItemFrame`（自带 `useState`）；人员/部门行在 `ids` 变化时才重新拉取 | 不新增 `memo`/`useCallback`（拆前没有，加了会改变渲染语义）；`useMemo` 依赖数组逐字保留 |
| **`SummaryEntry.fieldModes` 透传** | 明细表行用同一份 `fieldModes` 过滤隐藏列，人员/部门行不读它 | 类型与字段原样保留，`otherFilesEntry` 仍写 `fieldModes: {}` |
| **测试文件不可动** | `formFlowComponents.test.tsx`（15 例）从 `./ConfirmSummaryList` 导入 `ConfirmSummaryList`，覆盖截断/展开、媒体懒加载、清单、人员部门、其他附件、隐藏列、异常值、四类媒体、矩阵 | 拆分后原样跑，用例数必须仍为 15 |

## 5. 验证计划

1. `cd mobile && npx tsc -b --noEmit`（每抽一个文件跑一次）
2. `cd mobile && npm test -- ConfirmSummary`（无同名测试文件，实际用 `npm test -- formFlowComponents`，期望 15 passed）
3. `cd mobile && npm run lint`
4. `cd .. && node scripts/health-metrics.mjs`（退出码 0，`frontendOverLines` 2 → 1）

## 6. 实测结果（2026-10-22）

| 文件 | 实测行数 | 职责 |
| --- | --- | --- |
| `components/ConfirmSummaryList.tsx`（改） | **96** | 壳：空态、`nested`、条目装配、`SummaryItem` 分发、递归 `renderNested` |
| `components/confirmSummaryFormat.ts`（新） | 251 | 摘要文案、值解析、紧凑文本与标签规则、类型判定 |
| `components/confirmSummaryEntries.ts`（新） | 108 | `SummaryRow`/`SummaryEntry`、条目收集、其他附件归属、`summarizeSchemaRows` |
| `components/usePickerIdentities.ts`（新） | 53 | 人员/部门补名 hook、回落文案、工号元信息 |
| `components/ConfirmSummaryViews.tsx`（新） | 89 | `SummaryItemFrame`、人员行、部门行 |
| `components/ConfirmSummaryDetails.tsx`（新） | 221 | `renderSummaryDetail`、清单/明细表/矩阵/定位/选项标签详情 |

目标文件 739 → **96**（-87%），新增 5 个文件全部 ≤400 行；`components/` 下的测试文件
（`formFlowComponents.test.tsx`、`DynamicFormRenderer.test.tsx`、`MediaPreview.test.tsx`）`git status` 无变更。

### 验证输出

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查 | `cd mobile && npx tsc -b --noEmit` | 退出码 0（每抽一个文件各跑一次，6 次全绿） |
| 单测（任务指定过滤） | `cd mobile && npm test -- ConfirmSummary` | 无同名测试文件（vitest `--passWithNoTests` 退出 0），故改用下一条 |
| 单测（相关子集） | `cd mobile && npm test -- formFlowComponents` | 1 文件 / **15 用例**通过（与拆前同数） |
| 单测（全量） | `cd mobile && npm test` | 48 文件 / **449 用例**全部通过 |
| Lint | `cd mobile && npm run lint` | 退出码 0；6 个文件 lint 无任何告警（存量 10 warning 全在未改动文件） |
| H7 | `node scripts/health-metrics.mjs` | 退出码 0；两端超标文件 **2 → 1**，目标文件已移出 >600 行清单 |

### 行为不变性的证据

1. **导出契约**：`export function ConfirmSummaryList` + `export default ConfirmSummaryList` 未改；
   `summarizeSchemaRows` 与类型 `SummaryRow` 由 `export { ... } from './confirmSummaryEntries'` 透出；
   `SummaryRow`、`SummaryEntry`、`LocationValue` 等内部类型不新增对外暴露面。
   四个调用方（`SubmitConfirmPage.tsx`、`DirectSubmissionDetailPage.tsx`、`ProcessDetailPage.tsx`、`TaskDetailPage.tsx`）
   与 `formFlowComponents.test.tsx` 的 import 语句**一字未改**。
2. **字符串字面量零漂移**：拆前 94 个去重字符串字面量在新 6 个文件中全部命中（缺失 0），
   且每个字面量的出现次数均不少于拆前（出现次数下降的字面量 0 个）。
3. **中文片段零漂移**：拆前 34 个去重中文片段全部命中（缺失 0）；新增中文均为本轮注释。
4. **空值文案逐项对照**（`未填写` 出现次数拆前 17 / 拆后 17）：人员选择器、部门选择器、清单（`未完成` → 未填写）、
   明细表、矩阵、定位、媒体、选项、文本、注册表兜底、明细表行摘要、矩阵空格子、清单结果未填，全部逐字保留。
5. **格式化口径逐项对照**：`/(\d+) 项/g` → `$1项`（清单 `1项异常`）、`${count}条明细`（无空格）、
   `${rows} 行 × ${cols} 列`（有空格）、`N张图片/N个视频/N段录音/N个附件`、`用户#id`、`部门 #id`（保留空格）、
   ` 等${n}项`、32 码点截断 + `…`、`约 ${Math.round(accuracy)} 米`、`${department} · 工号 ${employeeNo}` 全部原样。
6. **结构标记零漂移**：`aria-expanded` 2、`aria-controls` 1、`aria-hidden` 2、`data-testid` 1、
   `summary-detail-` 1、`confirm-summary*` 10、`confirm-row*` 10、`confirm-table-detail` 2、
   `confirm-matrix-detail` 1、`confirm-checklist-detail` 3、`af-matrix__` 5，拆前拆后计数一致。
7. **hook 语义未变**：`useMemo(summaryEntries)` 依赖 `[fieldModes, includeEditable, schema, values]`、
   `useMemo(unlinkedFiles)` 依赖 `[extraFiles, schema, values]`、明细表 `useMemo(tableRowIds)` 依赖 `[rows]`、
   人员/部门 effect 依赖 `[endpoint, ids]`，全部逐字保留；未新增 state / effect / 请求。
8. **递归渲染结构未变**：明细表行展开仍渲染同一个 `ConfirmSummaryList`（`nested` 为 true），
   只是改为由壳文件通过模块级 `renderNestedList` 回调注入以避开循环依赖；DOM 层级与 props 相同，
   `keeps hidden table columns out of both row summaries and expanded rows` 用例通过。

### 遗留风险

| 风险 | 说明 |
| --- | --- |
| `renderNested` 回调 | 若将来第三个地方需要"在详情里再嵌一层摘要"，只能继续透传该回调；已标注注释，避免有人直接 import 壳文件造成循环依赖 |
| 纯函数仍无新增单测 | 纪律要求不改测试文件；`confirmSummaryFormat.ts` / `confirmSummaryEntries.ts` 现在可直接单测，后续可补 `compactText`、`optionLabels`、`schemaHasFileId` 的用例 |
| 文件数量 | 单目录新增 5 个文件，依赖方向单向：`format ← entries / views / details ← 壳`，`views ← usePickerIdentities`，无环（`details` 不 import 壳） |
| 明细表行重渲染 | `renderNestedList` 是模块级函数，身份稳定；但 `TableSummaryDetail` 每次父级渲染仍会重算行摘要（拆前一致，未加 memo） |

