# 前端巨型组件拆分：`settings/Wecom.tsx`（629 行）

日期：2026-10-22 ｜ 目标：`frontend/src/pages/settings/Wecom.tsx`（629 行 → ≤600，理想 ≤400）
参照：[`frontend-form-wizard-decomposition-2026-10-22.md`](frontend-form-wizard-decomposition-2026-10-22.md)、
[`frontend-proc-detail-decomposition-2026-10-22.md`](frontend-proc-detail-decomposition-2026-10-22.md) 的拆法
（按变化原因切：纯逻辑 → hook → 视图；样式单独收口，供多个面板共用）

## 0. 一句话

这个文件是"企业微信接入配置页"的**一个页面壳 + 三块面板 + 三条数据链路 + 五个写操作 + 一套纯映射表**。
它们的变化原因互不相干：改同步卡片布局不动凭证表单，改轮询间隔不动消息投递，改状态文案不动请求。
行数只超阈值 29 行，因此不追求"大改"，只做**一次按职责的切分**，把 629 行压到 **79 行**、且每个新文件都远低于 400 行。

## 1. 当前职责清单与"变化原因"

| # | 职责 | 现状位置 | 变化原因（谁会来改它） |
| --- | --- | --- | --- |
| R1 | 类型：`Company` / `SyncJob` / `SyncStatus` / `Settings` / `FormValues` / `DeliveryStatus` / `AccessInfo` | 34-87 | 后端接口字段增减 |
| R2 | 纯映射与判据：`WECOM_GREEN`、`active()`、`syncPollInterval()`、`phaseLabels`、`statusMeta` | 89-110 | 阶段/状态文案、轮询间隔口径 |
| R3 | 样式表 `useStyles`（14 个 class，含两处媒体查询） | 112-183 | 纯视觉 |
| R4 | 公司选择 + 设置加载 + 表单回填（`form.setFieldsValue` + `setDirty(false)`） | 206-245 | 回填字段、回填时机 |
| R5 | 保存写操作（payload 组装：trim、空 secret 不下发、`scheduleTime` 格式化） | 263-287 | 提交契约 |
| R6 | 同步任务查询（`initialData` / `enabled` / `refetchInterval` 三处口径）+ 启动写操作 + `syncMode`/`startedJobId` | 247-255、289-299 | 任务接口、轮询策略 |
| R7 | 消息投递：投递统计查询（10s 轮询）、测试消息、重试失败消息 | 256-261、300-311 | 消息接口、重试语义 |
| R8 | 公网接入信息查询 + 域名验证文件探测（fetch + 文件名正则比对） | 224-228、312-324 | 校验方式、接入地址来源 |
| R9 | 视图一：连接配置表单卡片（11 个表单项 + 保存按钮 + 未保存提示） | 344-461 | 纯 UI 与表单字段 |
| R10 | 视图二：当前同步任务卡片（进度、四项指标、结果告警、启动控件、空态） | 465-568 | 纯 UI |
| R11 | 视图三：企业微信后台配置卡片（可信域名/主页/回调/验证文件） | 571-597 | 纯 UI |
| R12 | 视图四：接入状态链路卡片（4 段链路 + 投递统计与两个消息按钮） | 598-626 | 纯 UI |
| R13 | 页面壳：`PageContainer` + 两列栅格装配 + `canStart` 派生 | 337-343、462-464、569-570、627 | 布局与装配 |

行数分布：视图 JSX ≈ 290、hooks/请求 ≈ 130、样式 72、类型 54、映射表 22。

## 2. 切分方案与理由

三条数据链路各自只有一个消费面板，所以**查询下沉到面板所在 hook**，壳不再持有它们的中间态：

- 设置/公司/表单 → `useWecomSettings`（被左侧表单卡片与右侧同步卡片同时需要：`secretConfigured` 决定能否启动同步 → 必须留在壳级）
- 同步任务 → `useWecomSyncJob`（壳级：`job` 同时决定"开始同步"按钮文案与 disabled）
- 公网接入 + 验证探测 → 直接住在 `WecomAccessCard`（只此一处读）
- 投递统计 + 测试/重试 → 直接住在 `WecomReadinessCard`（只此一处读）

样式用 `createStyles` 导出成 `useWecomStyles`（与 `approval/wizardStyles.ts` 同构）。`antd-style` 按 token 缓存，
四个组件各调一次不会生成重复样式表；把 class 留在原地、只换文件，视觉零变化。

### state 归属

| state / 数据 | 归属 | 理由 |
| --- | --- | --- |
| `form` 实例、`companyId`、`dirty` | `useWecomSettings`（壳级） | 表单卡片写值、同步卡片读 `dirty` 决定能否启动，两侧共用 |
| `settingsQuery` | `useWecomSettings`（壳级） | 两个卡片 + 状态链路卡片都读；下沉会重复请求 |
| `startedJobId`、`syncMode` | `useWecomSyncJob`（壳级） | `startedJobId` 与 `companyId` 必须同步重置（见 §4 R1）；`syncMode` 与启动按钮同属一个交互 |
| `jobQuery` | `useWecomSyncJob`（壳级） | `initialData` 依赖 `settings.latestJob`，壳是唯一同时看得到两者的地方 |
| `accessQuery` / 验证 `mutation` | `WecomAccessCard`（局部） | 唯一消费者 |
| `deliveryQuery` / 测试、重试 `mutation` | `WecomReadinessCard`（局部） | 唯一消费者；`refetchInterval: 10_000` 随组件挂载不变（卡片始终渲染） |
| `scheduleEnabled`（`Form.useWatch`） | `WecomConnectionCard` | 只用于"执行时间/模式"两个控件的 `disabled`，跟着表单走 |

**必须提升（不能下沉）**：`form`、`companyId`、`dirty`、`settingsQuery`、`jobQuery`+`startedJobId`。
**可以下沉的**：`accessQuery`、`deliveryQuery`、`scheduleEnabled`。
**effect 不变**：两处 `useEffect`（首屏选公司、settings 回填）位置与依赖数组原样搬进 `useWecomSettings`，
仍在"查询之下、渲染之上"，回填顺序不变。

## 3. 拆分后的文件清单

| 文件 | 职责（一句话） | 估算 | 实际 | props / 参数契约 |
| --- | --- | --- | --- | --- |
| `Wecom.tsx`（改） | 页面壳：两个壳级 hook + `canStart` 派生 + 栅格装配；保留 `syncPollInterval` 再导出 | ~65 | **79** | 无 props（默认导出 `WecomPage` 不变） |
| `wecomModel.ts`（新） | 全部类型 + 绿色常量 + `active` / `syncPollInterval` / 阶段与状态映射 | ~95 | **82** | 入参为数据，无 React 依赖 |
| `wecomStyles.ts`（新） | `useWecomStyles`：14 个 class 原样搬迁 | ~80 | **76** | 无参数 |
| `useWecomSettings.ts`（新） | 公司列表 + 设置查询 + 回填 effect + 保存 mutation + `dirty` + 切公司 | ~105 | **101** | 无参数；返回 `FormInstance` 与数据 |
| `useWecomSyncJob.ts`（新） | 任务查询（含 `initialData`/轮询）+ 启动 mutation + `syncMode`/`startedJobId` | ~80 | **49** | `{ companyId, settings }` |
| `WecomConnectionCard.tsx`（新） | 连接配置表单卡片（含 `Form.useWatch` 与未保存提示） | ~135 | **176** | `{ form, companies, companiesLoading, companyId, settings, loading, dirty, saving, onDirty, onSelectCompany, onSave }` |
| `WecomSyncCard.tsx`（新） | 同步任务卡片：进度、四项指标、告警、启动控件、空态；本地 `Metric` | ~130 | **141** | `{ job, loading, canStart, syncMode, onChangeMode, starting, onStart }` |
| `WecomAccessCard.tsx`（新） | 后台配置卡片 + 接入信息查询 + 验证文件探测 mutation | ~80 | **64** | 无 props |
| `WecomReadinessCard.tsx`（新） | 接入状态链卡片 + 投递统计查询 + 测试/重试 mutation | ~90 | **65** | `{ companyId, settings }` |

新文件全部 ≤176 行；目标文件 629 → **79**（-87%）。表单卡片偏大是刻意的：11 个表单项的 JSX 拆开反而要
在三个文件之间来回读校验规则，违背"同一个变化的代码放在一起"。

## 4. 风险与对策

| # | 风险 | 对策 |
| --- | --- | --- |
| R1 | **切换公司时任务残留**：原来 `startedJobId` 与 `companyId` 在同一个事件里重置；拆分后分属两个 hook，若用 `useEffect` 监听 `companyId` 重置，会先带着旧 `startedJobId` 渲染一帧并发起一次陈旧请求 | 保留同步重置：壳里 `onSelectCompany = (v) => { sync.resetStartedJob(); settings.selectCompany(v); }`，同一个 React 事件批处理，无中间渲染 |
| R2 | **保存后回填冲掉输入**：`settings` 查询刷新会触发回填 effect 把表单重置 | 保存成功后仍走 `queryClient.setQueryData(['wecom-settings', companyId], saved)`（不改失效策略），并且只清空 `secret`/`agentSecret` 两个字段；回填 effect 的依赖数组 `[form, settings]` 与 `setDirty(false)` 原样保留 |
| R3 | **重复请求**：三条查询下沉后面板若被条件渲染会反复卸载/挂载 | 四个卡片都无条件渲染（与原 JSX 一致），查询 `enabled` 条件与 `queryKey` 一字不改；`accessQuery`/`deliveryQuery` 的轮询间隔（`10_000`）与 `wecom-sync-job` 的 `1000/ false` 完全保留 |
| R4 | **i18n key 搬迁**：本页文案全部内联中文，无 `formatMessage` | 文案逐字复制，不重写；无需搬迁 key。若后续接入 i18n，改文案只需改对应面板 |
| R5 | **antd 受控属性漂移**：`aria-label`、`placeholder`、`requiredMark`、按钮文案被测试直接断言 | 表单 JSX 整段搬移，仅把 `setDirty(true)` 换成 `onDirty()`、`saveMutation.mutate` 换成 `onSave`，其余属性零改动 |
| R6 | **`Form.useWatch` 换宿主**：从页面组件移到表单卡片 | 卡片就在 `<Form>` 外层同一子树内，watch 语义不变；父组件不再因该值重渲染，属于不可见的渲染次数差异 |
| R7 | **测试导入路径**：`Wecom.test.tsx` 从 `./Wecom` 同时导入默认导出与 `syncPollInterval` | `Wecom.tsx` 重新导出 `syncPollInterval`（`export { syncPollInterval } from './wecomModel'`），测试文件不改 |

## 5. 验收

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 类型 | `cd frontend && CODEBUDDY_SAFE_DELETE_ENABLED=0 npm run tsc:fast` | 退出码 0（每抽一个文件跑一次，共 4 次全绿） |
| 测试 | `npm test -- settings` | 4 个文件 / 21 个用例全过，其中 `Wecom.test.tsx` 5 个 |
| 边界 | `npm run check:boundaries` | 存量 8 条，无新增 |
| H7 | `node scripts/health-metrics.mjs` | 退出码 0；`frontendOverLines` **6 → 5**，待拆清单 24 → 23 |
| 风格 | `npx biome lint <9 个文件>` | 0 问题 |

行为不变性：默认导出 `WecomPage` 与 `pages/registry.ts` 的 `./settings/Wecom` 均未改；表单字段名、校验规则、
`PUT` payload（trim、空 secret 不下发、`scheduleTime` 格式化为 `HH:mm:ss`）、任务轮询（活跃 1000ms / 其余关闭）、
投递统计轮询（10s）、各查询的 `queryKey` 与 `enabled` 条件全部原样保留；文案逐字未改。

遗留风险：`WecomConnectionCard` 176 行，若后续再加表单项（如多应用 AgentId 列表）需再拆出"凭证区/同步区"两个 section；
`useWecomSettings` 的 `selectCompany` 与 `useWecomSyncJob.resetStartedJob` 靠页面壳按顺序调用配对，
新增"切换公司"入口时必须同样成对调用，否则会出现跨公司的任务残留。
