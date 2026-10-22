# 移动端巨型组件拆分：`fields/ChecklistField.tsx`（656 行）

日期：2026-10-22 ｜ 目标：`mobile/src/features/forms/fields/ChecklistField.tsx`（656 行 → ≤600，理想 ≤400）
参照：[`mobile-file-upload-field-decomposition-2026-10-22.md`](mobile-file-upload-field-decomposition-2026-10-22.md)（845 → 132）的拆法：先值/纯函数，再 hook，最后视图，按变化原因切。
范围边界：只动 `mobile/src/features/forms/fields/**`（可新增文件，测试文件一字不改）+ 本文件；不新增依赖，不碰 `mobile/src/shared/**`、`features/forms/components/**`、`frontend/**`、`backend/**`。

## 1. 现状职责清单与"变化原因"

| # | 职责 | 现状行号 | 变化原因（谁会来改它） |
| --- | --- | --- | --- |
| R1 | 值契约与配置解析：`ChecklistEntry`/`ChecklistValue`/`ChecklistItemDef`/`ChecklistResultOption`、默认结果与颜色、`checklistItems`/`checklistResults` | 12-96 | schema 字段属性（检查项、结果集、颜色） |
| R2 | 表单值 ↔ 内部 entries：`entriesFromValue`（兼容 `itemId`/`result`/`remark`/`photos` 旧键）、`checklistSummary`（汇总文案）、`entryHasContent` | 600-656 | 值协议、确认页摘要 |
| R3 | entries 状态与回写：初始值、外部值同步 effect、`updateEntry`/`setStatus`/`setAll` → `onValueChange` | 104、132-158 | 表单联动口径 |
| R4 | 展开态：`expandedIds`、`expandItem`（自动收起"无内容"的其它展开项）、`collapseItem` | 103、109-130 | 交互细节 |
| R5 | 只读视图：结果色标签、描述、`ReadonlyMediaList` | 164-191 | 审批页展示 |
| R6 | 卡片视图：未选态的结果按钮组、已选态的取消/添加描述、展开后的描述与照片 | 263-371 | 单卡 UI |
| R7 | 照片上传：拍照/相册两个入口、平台 `chooseImages` 与 `<input type="file">` 两条分支、失败留存与重试、预览 object URL 生命周期、多图查看器 | 373-598 | 上传协议与预览实现（内存泄漏高发区） |
| R8 | 壳：标题/必填星/描述/错误、一键勾选与一键取消、列表组装、只读分支 | 98-107、160-261 | 字段外壳 |

行数分布：纯逻辑（R1/R2）约 150 行，状态 hook（R3/R4）约 60 行，视图（R5/R6）约 200 行，照片上传（R7）约 226 行，壳（R8）约 110 行。

三个自然断层：
1. **零 React 的 R1/R2**：被 `fieldRegistry`、`ConfirmSummaryDetails`、`confirmSummaryEntries`、`confirmSummaryFormat` 跨文件消费，必须集中且可从 `./ChecklistField` 继续具名导入。
2. **有状态无 JSX 的 R3/R4**：entries 与展开态是两套独立状态，但都只服务"列表交互"，放一起改一处不牵动上传。
3. **R7 是唯一持有 `URL.createObjectURL` 的地方**，和列表渲染混在一个 226 行的函数里，改上传必须跨读卡片视图。

## 2. 组件 / hook 切分方案与理由

```
ChecklistField.tsx           ← R8 壳 + 对外再导出
├── checklistValue.ts        ← R1/R2：类型、默认结果、配置解析、值归一化、摘要
├── useChecklistEntries.ts   ← R3/R4：entries/展开态状态机与回写
├── ChecklistCard.tsx        ← R5/R6：只读卡片列表 + 可编辑单卡
│   └── ChecklistPhotoUpload.tsx ← R7：拍照/相册、上传重试、预览 URL、查看器
```

**切法理由**

1. **值模块独立**（`checklistValue.ts`）：`checklistItems`/`checklistResults`/`entriesFromValue`/`checklistSummary` 被 `fieldRegistry.ts:14-18,448`、`ConfirmSummaryDetails.tsx:3`、`confirmSummaryEntries.ts:1`、`confirmSummaryFormat.ts:1` 具名导入。值归一化要同时兼容 `itemId/result/remark/photos` 与 `id/status/description/images` 两套键，集中一处，避免确认页与字段各写一份导致口径漂移。
2. **entries hook 独立**（`useChecklistEntries.ts`）：初始值 `useState(() => entriesFromValue(...))` + 外部值同步 effect + 三个写值函数共用同一份 `entries` 快照；与"展开态只收起空项"的交互规则同属列表交互，放一起后壳只剩组装。
3. **卡片视图独立**（`ChecklistCard.tsx`）：`CheckCard` 的 props 已是纯数据 + 三个回调，唯一外部依赖是 `ChecklistPhotoUpload`；把只读卡片列表也放这里，是因为两者都按"一条 entry → 一张卡"渲染，改展示结构只动一个文件。
4. **照片上传独立**（`ChecklistPhotoUpload.tsx`）：全文件唯一持有 object URL 的地方。原实现用 `previewUrlsRef`（id → blob URL）+ `photosRef`（最新照片快照，供连续上传时算剩余额度）两张表，`removePhoto`/卸载两处 revoke。整段逐字搬迁、不重写，避免拆出时改变 revoke 时机。
5. **壳只留组装**：`label`/`required`/`description`/`error`/`allChecked`/一键勾选与列表 map 留在壳里；这些是"字段契约"层（`MobileFieldProps`），下沉会让 hook 依赖 schema。
6. **不造转发壳**：壳文件用 `export { ... } from './checklistValue'` 与 `export type { ... } from './checklistValue'` 原样透出 4 个函数 + 4 个类型，**所有导入路径与符号名零改动**。

### state / ref 归属

| state / ref | 归属 | 理由 |
| --- | --- | --- |
| `entries` | `useChecklistEntries` | 唯一写者是列表交互；与 `onValueChange` 回写同一处 |
| `expandedIds` | `useChecklistEntries` | `expandItem` 需要同时读 entries 判断"有无内容" |
| `previews` + `previewUrlsRef` + `photosRef` | `ChecklistPhotoUpload` | 只服务照片预览与连续上传额度 |
| `uploading` / `failedFiles` / `viewerIndex` | `ChecklistPhotoUpload` | 上传会话内状态 |
| `viewerIndex` | `ChecklistPhotoUpload` | 查看器开关 |

**没有新增 state、没有新增 effect、没有新增请求**；effect 数量与依赖语义逐字保留（外部值同步 1 个、预览拉取 1 个、卸载清理 1 个）。

## 3. 新文件清单

| 路径 | 职责 | 行数估算 | props / 参数契约 |
| --- | --- | --- | --- |
| `fields/ChecklistField.tsx`（改） | 壳：标题/必填/描述/错误、一键勾选、列表组装、只读分支、对外再导出 | ~120 | `MobileFieldProps`（不变） |
| `fields/checklistValue.ts`（新） | 4 个类型、默认结果/颜色、`checklistItems`/`checklistResults`、`entriesFromValue`、`checklistSummary`、`entryHasContent` | ~140 | `MobileSchemaNode` / `unknown` |
| `fields/useChecklistEntries.ts`（新） | entries 初始值、外部值同步、`updateEntry`/`setStatus`/`setAll`/`clearAll`、展开态 | ~90 | `{ nodeId, value, items, onValueChange }` |
| `fields/ChecklistCard.tsx`（新） | `ChecklistReadonlyList` + `CheckCard`（结果按钮组、取消、添加描述、展开详情） | ~150 | `{ items, entries, results }` / `{ item, entry, ... }` |
| `fields/ChecklistPhotoUpload.tsx`（新） | 拍照/相册、平台与 input 双分支、上传失败重试、预览 URL 创建/回收、`ImageViewer.Multi` | ~240 | `{ photos, maxCount, onChange }` |

对外导出保持不变：`ChecklistField`、`checklistItems`、`checklistResults`、`entriesFromValue`、`checklistSummary` 与类型 `ChecklistResultOption`/`ChecklistItemDef`/`ChecklistEntry`/`ChecklistValue` 全部仍从 `./ChecklistField` 可取；原文件无 default 导出，本次不新增。

## 4. 风险点与对策

| 风险 | 具体表现 | 对策 |
| --- | --- | --- |
| **对象 URL 释放时机** | 三处泄漏点：删除单张（`removePhoto` 立即 revoke）、卸载（effect cleanup 全量 revoke）、拉取 blob 期间卸载（`cancelled` 标记丢弃结果） | 整段搬迁，`previewUrlsRef` 仍是唯一真源；`cancelled` 标记与 cleanup 依赖 `[]` 原样保留；不把创建/回收拆到两个文件 |
| **连续上传额度** | `photosRef.current` 是同步快照，`uploadFiles` 循环内逐张追加后再 `onChange`，避免闭包里的 `photos` 过期导致超出 `maxCount` | `photosRef.current = photos` 仍在函数体首行，循环里的 `photosRef.current = [...photosRef.current, dto]` 原样保留 |
| **批量设置语义** | 一键勾选=全部置为 `results[0].id`（不清描述）；一键取消=全部置 `null`（**不清描述**，与 `setStatus(null)` 不同） | `setAll` 与壳内 `clearAll` 分别实现，两者都不动 `description`；`setStatus(null)` 仍清空描述 |
| **必填校验时机** | 错误只在 `fieldError(props)` 里按当前值计算，输入时不即时校验 | 壳仍在渲染期计算，不新增 `useEffect`/本地错误态 |
| **展开自动收起** | `expandItem` 收起其它"描述为空且无图"的展开项 | 逻辑整体搬迁，`entryHasContent` 与 `expandItem` 同文件，判断口径只有一份 |
| **只读分支** | 只读态不显示错误、不渲染上传入口，图片走 `ReadonlyMediaList` | 只读卡片列表独立成组件，仍接收同一份 `items`/`entries`/`results` |
| **外部值回填** | effect 依赖 `[props.value, items]`；`items` 是 `useMemo(props.node)`，node 身份不变则 entries 不被覆盖 | hook 内保留同一依赖数组，`items` 由壳 `useMemo` 传入 |
| **测试文件不可动** | `fields.test.tsx` 4 个 checklist 用例（描述显隐、自定义结果色、上传失败重试、只读历史图） | 拆分前后用例数与断言不变；DOM class 与 aria-label 逐字保留 |

## 5. 验证计划

1. `cd mobile && npx tsc -b --noEmit`（每抽一个文件跑一次）
2. `cd mobile && npm test -- Checklist`（无匹配文件，改用 `npm test -- src/features/forms`，并说明原因）
3. `cd mobile && npm run lint`
4. `cd .. && node scripts/health-metrics.mjs`（退出码 0，两端超标文件 1 → 0）

## 6. 实测结果（2026-10-22）

| 文件 | 实测行数 | 职责 |
| --- | --- | --- |
| `fields/ChecklistField.tsx`（改） | **129** | 壳：标题/必填/描述/错误、一键勾选、列表组装、只读分支、对外再导出 |
| `fields/checklistValue.ts` | 120 | 类型、默认结果/颜色、配置解析、值归一化、摘要、`entryHasContent` |
| `fields/useChecklistEntries.ts` | 89 | entries/展开态状态机、三种写值路径与回写 |
| `fields/ChecklistCard.tsx` | 148 | 只读卡片列表 + 可编辑单卡（结果按钮组、取消、添加描述、展开详情） |
| `fields/ChecklistPhotoUpload.tsx` | 267 | 拍照/相册、上传失败重试、预览 URL 生命周期、多图查看器 |

目标文件 656 → **129**（-80%），新增 4 个文件全部 ≤400 行。测试文件与 `features/forms/components/**` 的 `git status` 无变更。

### 验证输出

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查 | `cd mobile && npx tsc -b --noEmit` | 退出码 0 |
| 单测（任务指定过滤） | `cd mobile && npm test -- Checklist` | 无匹配测试文件（vitest 过滤的是文件名），退出码 0；改用下一条 |
| 单测（相关子集） | `cd mobile && npm test -- src/features/forms` | 22 文件 / **278 用例**通过 |
| 单测（全量） | `cd mobile && npm test` | 48 文件 / **449 用例**全部通过 |
| Lint | `cd mobile && npm run lint` | 退出码 0；5 个改动/新增文件单独 lint「Checked 5 files，无告警」；存量 10 warning 在 `shared/api/http.test.ts` 等未改动文件 |
| H7 | `node scripts/health-metrics.mjs` | 退出码 0；`frontendOverLines` **1 → 0**（两端超标文件 0 个） |

### 行为不变性的证据

1. **导出契约**：`ChecklistField` 仍是唯一组件导出、props 仍为 `MobileFieldProps`（原文件无 default 导出，本次也没有新增）；
   `checklistItems` / `checklistResults` / `entriesFromValue` / `checklistSummary` 与 4 个类型由 `export { ... } from './checklistValue'` 透出。
   `fieldRegistry.ts`、`ConfirmSummaryDetails.tsx`、`confirmSummaryEntries.ts`、`confirmSummaryFormat.ts`、`fields.test.tsx` 的 import 路径与符号**一字未改**。
2. **文案零漂移**：拆前 31 个去重中文片段在新 5 个文件中**全部命中**（缺失 0）；新增中文仅为本轮注释与 hook 内部注释。
3. **字符串字面量零漂移**：拆前 29 个去重字面量（class 名、`aria-label` 模板、端点 `/api/mobile/files`、`accept`、Toast 文案）**全部命中**（缺失 0）。
4. **批量语义未变**：一键勾选 → 全部置 `results[0].id`；一键取消 → 全部置 `null`；两者都**保留**描述与照片，与拆前一致；单卡"点击取消选择"仍清空描述。
5. **上传链路未变**：端点 `/api/mobile/files`、`uploadMobileFile`、失败原因透出（`uploadFailureMessage`）、失败文件留存 + 「重试」、平台 `chooseImages(remaining, source)` 与 `<input type="file">` 两条分支、默认 `photoMaxCount` 9，逐字搬迁。
6. **effect / ref 语义未变**：3 个 effect 全部保留——entries 同步（依赖 `[value, items]`）、预览拉取（依赖 `[photos]`）、卸载清理（依赖 `[]`）；`previewUrlsRef` / `photosRef` 仍是唯一快照来源。

### 遗留风险

| 风险 | 说明 |
| --- | --- |
| 对象 URL 释放 | 创建/回收逻辑逐字搬迁：删除单张时立即 revoke 并删 Map 项、卸载时全量 revoke 并 clear、blob 返回前卸载用 `cancelled` 丢弃。未覆盖的仍是拆前同样存在的窄窗口——"blob 已取到、`createObjectURL` 前组件卸载"；未引入新泄漏点 |
| 预览请求无取消 | `fetchMobileFileBlob` 不支持 `AbortSignal`，卸载后请求仍会跑完（结果被 `cancelled` 丢弃），与拆前一致 |
| 预览 URL 依赖远端 id | 预览以 `photo.id` 为键，同一 id 内容被替换时不会重建 URL（拆前如此）；本次未改该策略 |
| 展开自动收起规则 | `expandItem` 只收起"描述为空且无图"的其它项；规则与 `entryHasContent` 同文件，若要调整需注意别把有内容的展开项一起收掉 |
| 纯函数仍无新增单测 | 纪律要求不改测试文件；`checklistValue.ts` 与 `useChecklistEntries.ts` 现在可直接单测，后续可补 `entriesFromValue` 旧键兼容、`checklistSummary` 汇总的用例 |
