# 移动端巨型组件拆分：`fields/FileUploadField.tsx`（845 行）

日期：2026-10-22 ｜ 目标：`mobile/src/features/forms/fields/FileUploadField.tsx`（845 行 → ≤600，理想 ≤400）
参照：[`mobile-approval-records-decomposition-2026-10-22.md`](mobile-approval-records-decomposition-2026-10-22.md)（848 → 111）的拆法：先纯函数、再 hook、最后视图，按变化原因切。
范围边界：只动 `mobile/src/features/forms/fields/**`（可新增文件，测试文件一字不改）+ 本文件；不新增依赖，不碰 `mobile/src/shared/**`、全局 store、`frontend/**`、`backend/**`。

## 1. 现状职责清单与"变化原因"

| # | 职责 | 现状行号 | 变化原因（谁会来改它） |
| --- | --- | --- | --- |
| R1 | 表单值契约：`UploadItem` / `FileUploadValue`、队列阻塞标记（隐藏 Symbol 属性）、`createFileUploadValue` / `hasBlockingUploadQueue` | 18-48 | 校验层（`fieldRegistry.ts:424`）与 `AudioUploadField` 依赖 |
| R2 | 配置解析：endpoint / accept / multiple / preview / maxCount / maxDuration / capture / addLabel / unitLabel | 52-65 | 设计器字段属性 |
| R3 | 上传队列状态机：items / itemsRef、进度节流、失败、`FileStillProcessingError` 转"处理中"、续等、删除（会话内 vs 历史文件） | 349-497 | 上传协议、重试/删除策略 |
| R4 | 表单值回写：`commitItems` → `emitUploadValue`，`ready` 才进值、未完成置阻塞位 | 479-497 | 校验口径 |
| R5 | 外部值合并：`mergeReadyItems`（父表单回填/草稿恢复，保留会话内未完成项） | 107-114、517-542 | 草稿恢复口径 |
| R6 | 预览对象 URL 生命周期：拉 blob、`createObjectURL`、目标漂移时 revoke、卸载清理、并发去重 | 67-176、557-576 | 预览实现，内存泄漏高发区 |
| R7 | 校验：数量上限、视频时长上限、水印额外字段、HEIC 识别与转码、视频时长读取 | 715-845 | 服务端限制与机型兼容 |
| R8 | 行内文案/格式：状态、进度百分比与色调、类型标签、文件大小、上传提示 | 578-698 | 纯文案 |
| R9 | 列表行视图：缩略图/类型图标、名称、状态、进度条、错误、重试/继续等待/删除按钮 | 190-254 | 行 UI |
| R10 | 选择入口：平台 `chooseImages`（企微）与 `<input type="file">` 两条分支 | 256-302 | 平台能力差异 |
| R11 | 壳：`FieldShell`、只读态、错误优先级 `fieldError > localBlocker > localError` | 178-189 | 字段外壳 |

行数分布：纯逻辑（R1、R5、R7、R8）约 330 行，队列/预览（R3、R4、R6）约 230 行，视图（R9、R10、R11）约 170 行。

两个自然断层：
1. **零 React 的部分**（R1/R5/R7/R8）可以完全脱离组件，直接单测；
2. **有状态但无 JSX 的部分**（R3/R4/R6）是 hook，且 R6（对象 URL）与 R3（队列）的变化原因不同：预览是渲染问题，队列是上传协议问题，混在一起改上传要跨读预览的 ref 生命周期。

## 2. 组件 / hook 切分方案与理由

```
FileUploadField.tsx（壳：FieldShell + 选择入口 + 只读态 + 对外再导出）
├── useUploadQueue.ts            ← R3/R4：队列状态机、上传/续等/删除、值回写
│   ├── fileUploadValue.ts       ← R1/R5：类型、阻塞值契约、ready 派生、外部值合并
│   └── fileUploadRules.ts       ← R2/R7：配置解析、数量/时长校验、水印、HEIC/时长读取
├── useUploadPreviewUrls.ts      ← R6：每个 ready 图片一条对象 URL，负责创建与释放
│   └── fileUploadValue.ts       ← 复用 fileContentUrl / isImageContentType
├── FileUploadItemRow.tsx        ← R9：单行视图
│   └── fileUploadLabels.ts      ← R8：状态/进度/类型/大小/提示文案
└── fileUploadLabels.ts          ← R8（上传入口 hint 也用）
```

**切法理由**

1. **值契约独立**（`fileUploadValue.ts`）：`createFileUploadValue` / `hasBlockingUploadQueue` 被 `AudioUploadField.tsx:8` 与 `fieldRegistry.ts:9,424` 跨文件消费，且 Symbol 隐藏属性是"数组 + 不可枚举标记"的取巧契约，必须集中一处，否则任何一处复制都会让校验失配。
2. **校验独立**（`fileUploadRules.ts`）：`maxCountError` / `videoDurationError` / `uploadExtraFields` / `isHeicFile` 已被 `media-upload.test.tsx:6` 直接单测；HEIC 转码与视频时长读取是浏览器能力探测（canvas / `<video>` / object URL），与队列协议无关。
3. **预览 hook 独立**（`useUploadPreviewUrls.ts`）：这是全文件唯一持有 `URL.createObjectURL` 的地方。它需要三张 Map（url / remoteId / in-flight）才能保证"同一 localId 换远端 id 时不泄漏、卸载时清空"。把它和队列放一起，会让上传重试路径看起来也在管预览。拆出后这个 hook 只依赖 `items` + `itemsRef` + `previewImages`，输入输出单一。
   - **为什么把 `itemsRef` 传进去而不是自己维护**：异步 `.then` 里要判断"这个 localId 现在是否还指向同一个 remoteId"，原实现用 `commitItems` 同步写的 `itemsRef`；hook 内再建一个 ref 会在状态提交与 effect 之间产生窗口，导致误 revoke 或漏 revoke。传入同一个 ref 是零语义差异的做法。
4. **队列 hook 独立**（`useUploadQueue.ts`）：约 200 行，包含"失败 vs 仍在处理"的分叉（`FileStillProcessingError`）与"会话内创建的才删远端"的删除策略。这些是上传协议知识，不是 UI 知识。hook 只暴露 `items / itemsRef / commitItems / addFile / removeItem / queueFileUpload / resumeProcessing / localError / setLocalError / readyValues`。
5. **行视图独立**（`FileUploadItemRow.tsx`）：单行 JSX 60 余行 + 三个回调，独立后加字段（例如显示时长）只动一个文件。
6. **文案独立**（`fileUploadLabels.ts`）：8 个纯函数被行视图与上传入口共同使用；文案是"看一眼就能改"的映射，独立后不在视图里散落三元表达式。
7. **壳只留选择入口与组装**：`chooseImages` 分支要同时读 `itemsRef`、`readyFiles`、`maxCount` 并写 `commitItems`，这是"字段组件"的编排职责，下沉到 hook 会把平台分支也塞进队列，反而增加耦合。
8. **不造转发壳**：测试与 `AudioUploadField` / `fieldRegistry` 都从 `./FileUploadField` 具名导入，因此壳文件用 `export { ... } from './fileUploadRules'` 原样透出 4 个校验函数与 2 个值函数，外加 2 个类型；**导入路径与符号名零改动**。

### state / ref 归属

| state / ref | 归属 | 理由 |
| --- | --- | --- |
| `items` + `itemsRef` | `useUploadQueue` | 队列是唯一写者；`itemsRef` 供异步回调与预览 hook 读取最新值 |
| `localError` | `useUploadQueue` | 由 `addFile` / 平台选择失败写入，与队列同生命周期 |
| `previewUrls` + 三张 Map + `mountedRef` | `useUploadPreviewUrls` | 只服务预览；卸载清理在这里闭环 |
| `inputRef` | `FileUploadField` | 纯 DOM 引用，未在别处使用（当前实现也未读取，保留原状） |
| 配置（endpoint/accept/...） | `FileUploadField` 每渲染解析 | 与原实现一致（原实现也是每次渲染从 props 读） |

**没有新增 state、没有新增 effect、没有新增请求**；effect 数量与依赖语义逐字保留（合并值 1 个、预览 1 个、卸载 1 个）。

## 3. 新文件清单

| 路径 | 职责 | 行数估算 | props / 参数契约 |
| --- | --- | --- | --- |
| `fields/FileUploadField.tsx`（改） | 壳：配置解析、`FieldShell`、只读态、平台/文件选择入口、对外再导出 | ~170 | `MobileFieldProps`（不变） |
| `fields/fileUploadValue.ts`（新） | `UploadItem`/`FileUploadValue`、阻塞标记、`readyFiles`/`asReadyFiles`、`mergeReadyItems`、`localBlocker`、内容类型判定 | ~130 | 入参为数组/`MobileFileDto`，零 React |
| `fields/fileUploadRules.ts`（新） | `resolveFileUploadConfig`、`maxCountError`、`videoDurationError`、`uploadExtraFields`、`isHeicFile`、HEIC 转码、视频时长读取 | ~170 | 入参 `MobileSchemaNode` / `File` |
| `fields/fileUploadLabels.ts`（新） | 状态文案、进度百分比/色调、类型标签与 class、大小格式化、上传提示 | ~100 | 入参 `UploadItem` / 字符串 |
| `fields/useUploadQueue.ts`（新） | 队列状态机、上传/进度/失败/续等/删除、值回写、外部值合并 | ~210 | `{ node, endpoint, config, onValueChange }` |
| `fields/useUploadPreviewUrls.ts`（新） | ready 图片 blob → object URL，目标漂移回收、并发去重、卸载清理 | ~110 | `(items, itemsRef, previewImages) => Record<localId, url>` |
| `fields/FileUploadItemRow.tsx`（新） | 单行：缩略图/类型图标、名称、状态、进度、错误、重试/删除 | ~95 | `{ item, previewUrl, onRetry, onDelete }` |

对外导出保持不变：`FileUploadField`、`createFileUploadValue`、`hasBlockingUploadQueue`、
`maxCountError`、`videoDurationError`、`uploadExtraFields`、`isHeicFile`、类型 `UploadItem` / `FileUploadValue`
全部仍从 `./FileUploadField` 可取。每个新文件 ≤400 行；目标文件目标 ≤200 行。

## 4. 风险点与对策

| 风险 | 具体表现 | 对策 |
| --- | --- | --- |
| **上传中断恢复** | `FileStillProcessingError` 必须仍落在 `processing`（不是 `failed`），否则用户重传导致视频重复；重试按钮文案必须仍是「继续等待」 | `failItem` / `resumeProcessing` 整段搬进 `useUploadQueue`，分支与注释逐字保留；`media-upload.test.tsx` 的转码用例断言 `upload` 只调用 1 次 |
| **对象 URL 释放时机** | 三个泄漏点：① localId 对应 remoteId 变化；② 卸载；③ 同一 localId 的旧 URL 被新 URL 覆盖 | hook 内保留 `previewUrlsRef` / `previewRemoteIdsRef` / `previewLoadsRef` 三张表与 `mountedRef`，创建/替换/回收顺序逐字复制；`createObjectURL` 仍只在 `isCurrentPreviewTarget` 通过后调用 |
| **并发上传** | 多文件同时上传时进度事件交错，`commitItems(next, false)` 不能回写表单值；`itemsRef` 必须同步更新 | `updateUploadProgress` / `commitItems` 的"仅本地、不 emit"参数保留；`commitItems` 仍是先写 ref 再 `setItems` |
| **大小/数量限制在校验层还是 UI 层** | `maxCountError` 同时被 `maxCountError(node, count)` 单测与 `beforeAddError` 调用；默认值（图片 20 / 视频 1）在 `defaultMaxCount` | 校验仍在**添加前**（`beforeAddError`），不在 schema 校验层新增规则；`defaultMaxCount` / `defaultUnitLabel` 与 `maxCountError` 同文件，避免两套默认值 |
| **对外导出漂移** | 4 个校验函数 + 2 个值函数被测试 / 同目录组件 / `fieldRegistry` 具名导入 | 壳文件 `export { ... } from` 透出，`git diff` 检查导出块；测试文件零改动 |
| **`props.onValueChange` 身份变化** | 队列 hook 的异步回调在 await 后调用 `emitUploadValue` | 与原实现相同：hook 每次渲染拿到最新 `onValueChange` 闭包；不加入依赖数组（原实现也没有），避免重发上传 |
| **循环依赖** | 预览 hook 需要 `itemsRef` 类型，队列 hook 需要值模块 | 依赖单向：`value/rules/labels ← useUploadQueue / useUploadPreviewUrls / FileUploadItemRow ← FileUploadField`；预览 hook 不 import 队列 hook，只接结构类型 `{ current: UploadItem[] }` |
| **只读态** | `ReadonlyMediaList` 由 `readyValues` 驱动，预览 hook 在只读态也要正常清理 | 只读分支保持原位置；`previewImages` 为 false 时 hook 走 `clearPreviewUrls()` 早退，与拆前一致 |
| **测试文件不可动** | `advanced-fields.test.tsx`（24 例，含上传/进度/预览/失败/历史文件解绑）、`media-upload.test.tsx`（8 例）、`native-fields.test.tsx` | 拆分前后跑 `npm test -- src/features/forms/fields`，用例数与结果必须一致（81 例） |

## 5. 验证计划

1. `cd mobile && npx tsc -b --noEmit`（每抽一个文件跑一次）
2. `cd mobile && npm test -- FileUpload`（无匹配文件，改用 `npm test -- src/features/forms/fields`，并说明原因）
3. `cd mobile && npm run lint`
4. `cd .. && node scripts/health-metrics.mjs`（退出码 0，`frontendOverLines` 3 → 2）

## 6. 实测结果（2026-10-22）

| 文件 | 实测行数 | 职责 |
| --- | --- | --- |
| `fields/FileUploadField.tsx`（改） | **132** | 壳：配置解析、`FieldShell`、只读态、平台/文件选择入口、对外再导出 |
| `fields/fileUploadValue.ts` | 127 | `UploadItem`/`FileUploadValue`、阻塞标记、ready 派生、外部值合并、内容类型判定 |
| `fields/fileUploadRules.ts` | 174 | 配置解析、数量/时长校验、水印字段、HEIC 转码、视频时长读取 |
| `fields/fileUploadLabels.ts` | 104 | 状态/进度/类型/大小文案、上传入口提示 |
| `fields/useUploadQueue.ts` | 261 | 队列状态机、上传/续等/删除、值回写 |
| `fields/useUploadPreviewUrls.ts` | 134 | 预览 object URL 创建与回收 |
| `fields/FileUploadItemRow.tsx` | 82 | 单条上传行视图 |

目标文件 845 → **132**（-84%），新增 6 个文件全部 ≤400 行。`mobile/src/features/forms/fields/` 下的测试文件（`advanced-fields.test.tsx`、`media-upload.test.tsx`、`native-fields.test.tsx` 等）`git status` 无变更。

### 验证输出

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 类型检查 | `cd mobile && npx tsc -b --noEmit` | 退出码 0 |
| 单测（任务指定过滤） | `cd mobile && npm test -- FileUpload` | 无匹配测试文件（vitest 以 `--passWithNoTests` 退出 0），故改用下一条 |
| 单测（相关子集） | `cd mobile && npm test -- src/features/forms/fields` | 7 文件 / **81 用例**通过（与拆前同数） |
| 单测（全量） | `cd mobile && npm test` | 48 文件 / **449 用例**全部通过 |
| Lint | `cd mobile && npm run lint` | 退出码 0；7 个新文件单独 lint「Checked 7 files，无告警」；存量 10 warning 在 `shared/api/http.test.ts` 等未改动文件 |
| H7 | `node scripts/health-metrics.mjs` | 退出码 0；`frontendOverLines` **3 → 2**，目标文件已移出 >600 行清单（最大文件行数由后端文件决定，工作区另有他人进行中的后端拆分，与本次无关） |

### 行为不变性的证据

1. **导出契约**：`FileUploadField` 仍是唯一组件导出且 props 仍为 `MobileFieldProps`（原文件无 default 导出，本次也没有新增）；
   `createFileUploadValue`、`hasBlockingUploadQueue` 由 `export { ... } from './fileUploadValue'` 透出，
   `maxCountError`、`videoDurationError`、`uploadExtraFields`、`isHeicFile` 由 `export { ... } from './fileUploadRules'` 透出；
   `UploadItem`、`FileUploadValue` 类型同样透出。`AudioUploadField.tsx`、`fieldRegistry.ts`、三个测试文件的 import 路径与符号**一字未改**，`tsc` 与 449 个用例共同证明契约未破。
2. **文案零漂移**：拆前文件 42 个去重中文片段在新 7 个文件中**全部命中**（缺失 0）；新增中文仅为本轮注释。
3. **字符串字面量零漂移**：拆前 75 个去重字符串字面量（class 名、`aria-label` 模板、错误文案、端点、状态字面量）在新文件中**全部命中**（缺失 0）。
4. **协议与限制值未变**：默认端点 `/api/mobile/files`（`resolveFileUploadConfig`）、`DEFAULT_FILE_ACCEPT = ''`、
   图片默认 20 张 / 视频默认 1 个 / 视频默认 60 秒（`defaultMaxCount`、`defaultUnitLabel`、`videoDurationError`）、
   水印默认 `AntFlow`，全部逐字搬迁。
5. **状态机未变**：`FileStillProcessingError` → `processing` + 保留 `remote`、重试按钮「继续等待」；
   历史文件（`createdInSession !== true`）删除只解绑不调 DELETE；`commitItems(next, false)` 的进度更新仍不回写表单值；
   `mergeReadyItems` 的"会话内未完成项保留"条件原样保留。
6. **effect / ref 语义未变**：3 个 effect（外部值合并、预览、卸载清理）全部保留，依赖数组分别为
   `[readyValues]`、`[items, previewImages, clearPreviewUrls, isCurrentPreviewTarget, publishPreviewUrls]`、`[clearPreviewUrls]`；
   `itemsRef` 仍是唯一被异步回调读取的队列快照，并原样传给预览 hook（不在 hook 内另建，避免提交窗口期误 revoke）。

### 遗留风险

| 风险 | 说明 |
| --- | --- |
| 对象 URL 释放 | 创建/回收路径与拆前一致：目标变化时 revoke、替换时 revoke 旧值、卸载时 `clearPreviewUrls(false)` 全量 revoke、blob 回来时若组件已卸载则不创建 URL。唯一未覆盖的是"blob 已创建 URL、但 `publishPreviewUrls` 触发渲染前组件被卸载"的极窄窗口——React 会在卸载后丢弃 setState，而 cleanup 已先执行，属拆前同样存在的行为，未引入新泄漏点 |
| 预览请求无取消 | `fetchMobileFileBlob` 不支持 `AbortSignal`，卸载后请求仍会跑完（结果被 `mountedRef` 丢弃）。拆前一致；若要彻底取消需改 `files.api.ts`（本次范围外） |
| 队列失败项只能手动清 | 与原实现一致：失败项留在列表里占位并阻塞提交，用户必须重试或删除；未新增自动清理 |
| 纯函数仍无新增单测 | 纪律要求不改测试文件；`fileUploadValue.ts` / `fileUploadLabels.ts` 现在可直接单测，后续可补 `mergeReadyItems`、`hasIncompleteUploads` 的用例 |
| 文件数量 | 单目录新增 6 个文件，依赖方向固定为单向：`fileUploadValue/rules/labels ← useUploadQueue / useUploadPreviewUrls / FileUploadItemRow ← FileUploadField`，无环 |
