# 类体积治理：上线前复核结论

日期：2026-10-20 ｜ 复核对象：`docs/class-decomposition-execution-2026-10-20.md` 及其已落地改动
复核问题：改动完成并上线后，是否影响系统正常运行

## 0. 先界定复核范围：这里有两件完全不同的事

上一版方案把"门禁"和"拆分"写在一起，容易被读成一次上线。实际是两类交付，风险差两个数量级：

| | A 类：本次已落地 | B 类：方案规划、尚未执行 |
| --- | --- | --- |
| 内容 | H7 进 `health-metrics.mjs`、基线、两份文档、一条 ADR | 拆 28 个超标类/文件 |
| 是否碰业务代码 | **否**（零行业务代码变更） | 是 |
| 是否碰数据库 | 否（当前最高迁移 V61，未新增） | 否（方案明确不碰 SQL） |
| 是否碰 API | 否 | 否（契约文件应逐字不变） |
| **运行时影响** | **无** | 有，需逐个评估 |

**结论先行**：A 类可以随时上线，不影响系统运行；B 类不能整体上线，须按 §8 分批并把移动端与引擎排除在外。

---

## 1. 总体风险等级

| 对象 | 风险等级 | 判据 |
| --- | --- | --- |
| A 类（本次改动） | **低（P3）** | 纯 CI 检查 + 文档。唯一影响是"CI 可能变红"，属流程摩擦，不是功能故障 |
| B-1 包内服务拆分（`WecomService` / `OptionSourceService`） | **低（P3）** | 生产调用方只在本包内，外部零影响 |
| B-2 桌面端页面拆分 | **低-中（P3/P2）** | 有 chunk 加载失败识别与重载引导；无业务逻辑变更 |
| B-3 `FormDefinitionService` | **中（P2）** | 被 **6 个模块** 依赖，但目标簇可用委托层隔离（见 §4.2） |
| B-4 引擎（`ProcessEngine` / `WorkflowRuntimeV2`） | **高（P1）** | 2 套可写运行时并存；改错直接影响审批推进，且难以回滚 |
| B-5 移动端页面拆分 | **高（P1）** | `/mobile/assets/` 强缓存 1 年且 `try_files =404`；移动端**无** chunk 失效兜底 |

---

## 2. 风险点清单

### 2.1 已落地改动（A 类）的风险

| ID | 风险 | 等级 | 后果 | 状态/对策 |
| --- | --- | --- | --- | --- |
| A-R1 | H7 误红阻塞紧急修复 | P3 | 新增一个 450 行的类即 CI 红，hotfix 被卡 | 失败信息已给出"先修路径"提示；合法放宽走 `--write` 并在 PR 说明 |
| A-R2 | `--write` 被用来"放水" | **P2** | 基线被悄悄放宽，门禁形同虚设 | **需 review 强制**：改 `baseline.json` 必须与"为什么接受"写在同一个 PR；CODEOWNERS 已覆盖 docs |
| A-R3 | 扫描路径失配 → 计数全 0 → 静默"变好" | **已修复** | 指标失效但显示通过 | 已加 `assertScanComplete`（java ≥150 / ts ≥300）+ 2 条 self-test 断言 |
| A-R4 | 跨平台行尾导致本地/CI 数字不一致 | P3 | 本地绿、CI 红 | **已实测排除**：CRLF 与 LF 下行数与方法数完全相同（1236/77 与 1403/76 均一致）；`.gitattributes` 强制 `eol=lf` |
| A-R5 | 指标计算拖慢 CI | P3 | metrics job 变慢 | 纯同步读 786 个文件，秒级；原 job 本就是"几秒跑完"的独立 job |

### 2.2 规划中的拆分（B 类）的风险

| ID | 风险 | 等级 | 后果 | 对策 |
| --- | --- | --- | --- | --- |
| B-R1 | **移动端 chunk 404 白屏** | **P1** | 部署瞬间旧页面引用已删除的 chunk；`/mobile/assets/` 缓存 1 年 + `=404` 不回落；移动端 `GlobalErrorBoundary` 只提供"重试"，而 React.lazy 的失败 import 会被缓存，重试必然再失败，用户只能手动整页刷新（且无提示） | **移动端拆分不随本次推进**；先补 `isChunkLoadError` → `location.reload()`（参照桌面端实现） |
| B-R2 | 引擎行为漂移 | **P1** | 审批推进/驳回/撤回错误，且难以察觉 | 引擎最后动；先定 V1/V2 唯一写入方；先补特征测试 |
| B-R3 | 事务边界被拆散 | P2 | 部分成功、数据不一致 | 已实测：`FormDefinitionService` 的 5 个 `@Transactional` 全在 CRUD 方法，目标拆走的 `validate*`/`project*`/`canonicalize*` **均无事务注解** → 风险低于预期。规则仍不变：新类不加事务 |
| B-R4 | Spring 自调用导致注解失效 | P2 | `@Transactional`/`@Cacheable` 静默失效 | 拆出的类由注入调用，禁止同类内部调用带注解方法。编译器与测试都发现不了，只能靠 review |
| B-R5 | 测试编译大面积失败 | **P2** | `new ProcessEngine(...)` 是**手动构造**（非 Spring 注入），共 **5 处**；新增构造参数会让这 5 处同时编译失败 | 拆分前统计 `new` 点；构造参数变化集中在同一个 PR 内改完 |
| B-R6 | 公开方法被外部调用，改签名即破坏兼容 | **P2** | `projectStarterSchema` 6 处、`projectStarterData` 5 处外部调用 | **必须保留原方法作为委托**，不让 6 个模块跟着改（详见 §4.2） |
| B-R7 | SQL 随方法一起搬走 | P2 | 新类变成第二个巨类；分页/数据权限谓词漂移 | 拆分不碰 SQL；`WecomService` 的 `JdbcTemplate` 直查尤其危险 |
| B-R8 | 与在途改动冲突 | **P2** | 工作区现有 55 个未提交修改（含 `ProcessEngine`、`WorkflowRuntimeV2`） | 按包串行；动某类前先确认它无在途改动 |
| B-R9 | 契约文件意外变化 | P2 | 前后端契约漂移 | `ApiContractSnapshotTest` + `antflow-api.json` 逐字比对；拆分 PR 中该文件必须零 diff |

---

## 3. 受影响模块与依赖关系（实测）

### 3.1 生产代码调用方

| 巨类 | 行/方法 | 被哪些模块引用 | 测试文件引用 | 扇出 |
| --- | --- | --- | --- | --- |
| `FormDefinitionService` | 1403 / 76 | `engine`、`form`、`form/options`、`form/runtime`、`mobile/workflow`、`process`、`task` | **14** | **7 个包（其中 6 个在本包外，最大）** |
| `ProcessEngine` | 1277 / 48 | `automation`、`engine/handler`、`mobile/workflow`、`task` | 8 | 4 个模块 |
| `WorkflowRuntimeV2` | 1236 / 77 | `engine`、`engine/handler`、`mobile/workflow`、`task` | 3 | 4 个模块 |
| `WecomService` | 1313 / 63 | `integration/wecom`（**仅包内**） | 5 | 1 个包 |
| `OptionSourceService` | 1044 / 65 | `form/options`（**仅包内**） | — | 1 个包 |

### 3.2 关键依赖链路（必须画清的两条）

```
自动化：AutomationJobScheduler ──> ProcessEngine ──> WorkflowRuntimeV2
        （DELAY/TRIGGER 到点触发）      （推进）         （V2 仲裁/分支）

移动端审批：MobileWorkflowController ──> MobileWorkflowService ──> ProcessEngine
                                    └─> ApprovalPreviewService ──> WorkflowRuntimeV2
                                        （active/processTree/formSchema/
                                          shouldAutoApprovePreview/previewAssignments）
```

**含义**：动 `WorkflowRuntimeV2` 不只是改引擎，`ApprovalPreviewService` 直接调它 6 个方法，
移动端"审批预览"会跟着变。这是 B-4 判为 P1 的直接原因。

### 3.3 一个反直觉但重要的判断

`FormDefinitionService` 扇出最大（7 个包，其中 6 个在本包外），但**它是低技术风险的**——因为要拆的都是
`validate*`/`project*`/`canonicalize*`，改的是**内部实现**，公开签名可原样保留（委托）。
反过来，`ProcessEngine` 扇出较小（4 模块），但拆它必然改构造签名 → 所有 `new ProcessEngine(...)` 崩。

> **扇出大小 ≠ 改动风险**。风险取决于"改的是内部实现还是公开签名"。

---

## 4. 兼容性评估

### 4.1 四个兼容面

| 兼容面 | 结论 | 依据 |
| --- | --- | --- |
| **HTTP API** | ✅ 不受影响 | 方案不改端点、不改 DTO；`antflow-api.json` 应逐字不变 |
| **数据库** | ✅ 不受影响 | 不新增迁移（当前 V61），不改 SQL，不改事务传播 |
| **前端产物** | ⚠️ 文件名会变 | 拆分改变 chunk 划分与 hash；`index.html` 是 `no-cache` 但 `/mobile/assets/**` 是 `immutable` 1 年 → 旧 chunk 在部署后 404 |
| **Java 内部调用** | ⚠️ 需委托层 | 6 类共 7 个方法被外部调用（§4.2） |

### 4.2 必须保留的委托层（兼容性关键）

实测外部调用次数：

| 方法 | 外部调用 | 处置 |
| --- | --- | --- |
| `projectStarterSchema` | 6 处 | **保留原方法**，内部 `return projector.projectStarterSchema(...)` |
| `projectStarterData` | 5 处 | 同上 |
| `starterFieldModes` | 5 处 | 同上（若搬走） |
| `canonicalizeStarterSubmission` / `canonicalizeStarterRevision` | 2 / 2 处 | 同上 |
| `filterVisibleSubmission` | 1 处 | 同上 |
| `validateSubmission` | 2 处 | 同上 |

> 上表 6 类共 7 个方法名；`validateStarterRevision`、`canonicalizeStarterData`、`leafFieldTypes` 等各有 1–2 处调用，处置原则相同。

**不要把"搬到新类"理解成"删掉旧方法并改所有调用点"**。后者会把 1 个 PR 的改动面从 1 个文件扩大到 6 个模块，
回归风险成倍上升，且评审无法验证。正确做法：旧方法是**门面**，新类是**实现**；等调用方自然收敛后再考虑删门面（本迭代不删）。

### 4.3 移动端与桌面端的兜底差异（决定能否一起上线）

| | 桌面端 | 移动端 |
| --- | --- | --- |
| 识别 chunk 失败 | ✅ `isChunkLoadError`（含 `Failed to fetch dynamically imported module`） | ❌ 无识别 |
| 恢复手段 | 重载按钮 + 网络恢复时自动 `location.reload()` | 仅 `resetErrorBoundary`（lazy 失败被缓存 → 重试必然再失败） |
| 缓存策略 | `hash: true`，同源静态资源 | `/mobile/assets/` `immutable` 1 年 + `try_files =404` |
| **结论** | 可随本次推进 | **不可**，须先补兜底 |

---

## 5. 回滚顾虑

| 对象 | 回滚方式 | 顾虑 |
| --- | --- | --- |
| A 类门禁 | `git revert` 单个 commit | **零风险**：不涉及运行时；回滚后只是失去检查 |
| B 类拆分 | 逐 commit revert | **可回滚的前提是"一个类一提交、不夹带功能"**。若拆分 PR 里顺手改了 bug 或 SQL，revert 会连带撤掉修复 |
| 引擎拆分 | revert + 数据核对 | **最难**：若已产生审批数据（任务/历史/分支计数），代码回滚不会回滚数据，需确认新旧逻辑对同一份数据的行为一致 |
| 移动端拆分 | revert + 重新构建 | 旧版本用户的浏览器仍缓存着**新** chunk 名，回滚后同样 404——**回滚不能修复已分发的客户端** |

**因此**：移动端问题不是"回滚能救"的问题，必须靠上线前补兜底来防。

**额外顾虑（当前就存在，非本次引入）**：工作区 55 个在途修改中多个文件行尾为 CRLF，
合并时 git 会整文件转 LF，产生"全文重写"式 diff，**真实改动会被行尾噪声淹没**。建议合并前单独跑一次
`git add --renormalize .` 并作为独立提交，避免与业务改动混在一起。

---

## 6. 验证与测试建议

### 6.1 A 类（上线前必做，5 分钟）

```bash
node scripts/health-metrics.mjs            # 期望：退出码 0，H7 与基线一致
node scripts/health-metrics.mjs --self-test # 期望：全部 ✓（含扫描守卫两条）
```

补充：在 CI 上确认 `metrics` job 绿（ubuntu + Node 22，纯内置模块，无网络依赖）。

### 6.2 B 类每一刀（合并前必做）

```bash
# 后端（需 PostgreSQL；本地无 Docker 时先 docker info 探活，CI 已配 postgres service）
cd backend && mvn -B test

# 只验行为不变时，至少跑目标类的测试 + 契约快照
cd backend && mvn -B test -Dtest=FormDefinitionServiceSchemaTest,ApiContractSnapshotTest

# 前端 / 移动端
cd frontend && npm run lint && npm test && npm run build
cd mobile && npm run lint && npm test && npm run build
```

### 6.3 分层验证矩阵

| 层 | 手段 | 覆盖什么 | 缺口 |
| --- | --- | --- | --- |
| 单元 | 已有类测试（14/8/5 个文件） | 方法级行为 | 构造式测试对签名变化敏感 |
| 契约 | `ApiContractSnapshotTest` | 端点/DTO 是否漂移 | 需 Docker/Testcontainers |
| 集成 | `PostgresTransactionalIntegrityIntegrationTest`（5475 行） | 事务不变量、乐观锁、幂等 | 单文件过大，失败定位难 |
| 前端 | vitest + 4 视口 Playwright + 视觉基线 | 交互与布局 | 不覆盖部署后 chunk 失效 |
| 部署 | `deploy.sh --self-test`、Flyway 前滚演练 | 发布链路 | 不覆盖浏览器缓存 |

**建议补一条当前没有的验证**：部署后再用**旧版本页面**（部署前打开的标签页）点击一个懒加载路由，
确认能正常恢复。这是 B-R1 唯一可靠的验证方式，现有测试矩阵完全覆盖不到。

### 6.4 上线后观察

| 指标 | 位置 | 异常信号 |
| --- | --- | --- |
| JS 异常率（`js_exception`，含 `ChunkLoadError`） | 移动端 telemetry | 部署后 10 分钟内突增 |
| 审批任务完成时延 | `t_task` / 监控 | 引擎拆分后若变长，说明推进逻辑有额外查询 |
| 自动化作业失败率 | `t_workflow_job` | `ProcessEngine` 拆分后的直接信号 |
| 企微同步成功率 | `t_workflow_job`（wecom 类型） | `WecomService` 拆分后的直接信号 |

---

## 7. 后续优化：哪些随本次推进，哪些留后续

### 7.1 可随本次（或紧接着）推进 —— 全部满足"运行时零影响"

| 项 | 理由 | 前置 |
| --- | --- | --- |
| H7 门禁 | 已完成，纯 CI | — |
| PR 模板 + `AGENTS.md` 提交纪律 | 纯文档 | — |
| `baseline.json` 变更的 review 规则 | 纯流程 | CODEOWNERS 已覆盖 |
| `git add --renormalize` 消除 CRLF 噪声 | 纯行尾，不改语义 | 与业务改动分开提交 |
| **包内拆分**：`WecomService`、`OptionSourceService` | 调用方仅在包内，外部零影响 | 先确认无在途改动 |
| 桌面端页面拆分 | 有 chunk 兜底；纯前端 | 视觉基线需同步刷新 |

### 7.2 建议留到后续迭代

| 项 | 为什么留 |
| --- | --- |
| **移动端页面拆分** | 需先补 chunk 失效自动重载（B-R1），否则一上线就可能白屏 |
| **引擎拆分** | 需先定 V1/V2 唯一写入方 + 补特征测试；当前 P1 |
| **`FormDefinitionService`** | 扇出 6 模块，虽是内部实现，但应在包内拆分验证过流程后再动 |
| ArchUnit 体积规则 | H7 已覆盖同一目标；ArchUnit 无内置行数条件，需自定义 `ArchCondition`，收益低于成本 |
| `PostgresTransactionalIntegrityIntegrationTest` 拆 `@Nested` | 纯测试重构，与生产风险无关，独立 PR 做 |
| 前端 bundle 预算收紧 | 与本次目标无关，避免一次改太多变量 |

---

## 8. 结构化复核结论

### 8.1 风险等级

| 交付面 | 等级 | 是否可上线 |
| --- | --- | --- |
| A 类：H7 门禁 + 基线 + 文档 | **P3 低** | ✅ **可立即上线**，不影响运行时 |
| B-1：包内服务拆分 | P3 低 | ✅ 可在本迭代内做 |
| B-2：桌面端页面拆分 | P3/P2 | ✅ 可做，需同步刷新视觉基线 |
| B-3：`FormDefinitionService` | P2 中 | ⚠️ 需保留委托层，建议在 B-1 之后 |
| B-4：引擎 | P1 高 | ❌ 本迭代不做 |
| B-5：移动端 | P1 高 | ❌ 本迭代不做（先补兜底） |

### 8.2 需重点确认的事项

1. **`--write` 的审批规则**：谁有权放宽 H7 基线？建议规定"改基线必须同 PR 说明 + 由 CODEOWNERS 审批"，否则门禁会退化成摆设。
2. **移动端 chunk 兜底是否排期**：这是 B-5 的唯一前置。不排期，移动端拆分就永远做不了。
3. **V1/V2 唯一写入方的定案时间**：H4 显示仍是 2 套。不定案就动引擎，等于把双写复杂度搬个位置。
4. **在途 55 个改动与本次提交的顺序**：先合并业务改动还是先合并门禁？建议**先门禁**（它只拦新增，不会与业务冲突），但要在合并前确认基线未因业务改动而偏小——本次已实测"无 HEAD 版本更大的文件"，基线安全。
5. **契约快照的执行环境**：`ApiContractSnapshotTest` 需要 PostgreSQL（Testcontainers）。本地 Docker 未运行时，拆分 PR 的契约验证只能靠 CI，**PR 合并前必须确认 CI 的 backend job 绿**，不能只看本地。
6. **CRLF 整文件重写的 diff**：合并前单独做一次 `--renormalize`，否则 review 会漏看真实改动。

### 8.3 推荐实施方案

**第一批（可立即上线，零运行时风险）**
合并 A 类改动。验收：`node scripts/health-metrics.mjs` 退出 0、`--self-test` 全绿、CI `metrics` job 绿。

**第二批（本迭代，P3 低风险）**
补 PR 模板 + `AGENTS.md` 提交纪律 → 拆 `OptionSourceService` 的 `fetch*` 簇（包内、无事务、纯 I/O）
→ 拆 `WecomService` 按三种失败语义 → 桌面端 `FormManagementWizard` / `proc/Detail`。
验收：每刀一个 commit；相关测试绿；`antflow-api.json` 零 diff；H7 对应数字下降。

**第三批（下一迭代，P2）**
`FormDefinitionService` 拆校验簇（≈700 行，走委托层）。前置：B-1 已跑通流程、无在途改动。
验收：`FormDefinitionService` ≤800 行；外部调用点零改动；`FormDefinitionServiceSchemaTest` 全绿。

**第四批（条件触发）**
移动端拆分：前置是把 `isChunkLoadError` + 自动重载补进 `GlobalErrorBoundary` 并有 E2E 覆盖。
引擎拆分：前置是 V1/V2 唯一写入方定案 + 特征测试补齐。

**贯穿全程**
H7 作为唯一客观进度尺；每条 PR 附"行数/方法数前后对比"；每批结束做一次"陌生开发者 30 分钟定位"验收。
