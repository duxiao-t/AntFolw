# 类体积治理：落地诊断与执行修正

日期：2026-10-20 ｜ 前置文档：[`class-decomposition-plan-2026-10-17.md`](class-decomposition-plan-2026-10-17.md)（下称"上一版"）
范围：`backend/`、`frontend/`、`mobile/` 的自有生产代码

## 0. 结论先说

**架构分层没有问题，不要为了"调整架构"去动分层**——`AGENTS.md` §3 的五条不变量（能力码、三层鉴权、
数据范围、移动端契约、迁移只前滚）都是对的，`ArchitectureBoundaryTest` 也在守着。真正的债务只有一件事：
**类内聚失效**，即一个类里塞了多个变化原因不同的职责。

上一版方案（10-17）已经把这件事分析清楚了，但它**停在纸面**：三天过去，体检脚本、H7 指标、PR 模板、
9 个目标类，一个都没建；`FormDefinitionService` 一行没减。所以本文件不复述拆分清单，而是回答三个问题：

1. 上一版为什么没被执行（§2）；
2. 它的度量本身有什么错（§3，实测：方法数虚高 8%–33%）；
3. 改成什么次序才推得动（§4 起，并且**已落地第一步**：H7 门禁已进 CI）。

---

## 1. 复核：方案在，落地为零

| 上一版承诺的抓手 | 位置 | 实测 |
| --- | --- | --- |
| 体检脚本 `class-size-report.mjs` | `scripts/` | ❌ 不存在（只有 `health-metrics.mjs`） |
| H7 超大文件数指标 | `docs/health-metrics.baseline.json` | ❌ 只有 H1–H6 → **本次已补** |
| PR 检查单 | `.github/PULL_REQUEST_TEMPLATE.md` | ❌ 无（只有 `CODEOWNERS`、`workflows`） |
| ArchUnit 体积规则 | `ArchitectureBoundaryTest.java` | ❌ 只有 3 条依赖方向规则 |
| 9 个目标类（投影器/校验器/决策器等） | `backend/src/main/java` | ❌ 0 个已创建 |

同期提交 `b220a37 文档: 类体积治理方案与忽略 .ai-memory` 只加了文档。**这不是执行力问题，是方案形态问题**（§2）。

---

## 2. 上一版为什么停在纸面：四个可修缺陷

| 缺陷 | 具体表现 | 后果 | 本版修正 |
| --- | --- | --- | --- |
| **次序反了** | 门禁放在"第三期（最后）"，等于先拆完再上锁 | 拆的过程中没有任何力量阻止新代码继续变胖；拆完也没人守 | 门禁**先上**、只拦新增（§4） |
| **第 0 步是门槛而不是抓手** | 要求先写 `class-size-report.mjs` 再开工 | 写脚本比加一条指标更"麻烦"，于是卡在第一步 | 直接复用 `health-metrics.mjs` 既有机制加 H7，**已完成** |
| **拆分单元太大** | 一个类拆 6 个，单个 PR 预计 400+ 行改动 | 评审无法验证"行为不变"，只能靠信任，于是拖着 | 改为**一次一个方法簇**，单次 ≤300 行（§5） |
| **没有样板** | 全是设计，没有一次可复制的落地示范 | 后来者不知道"第一刀"该从哪切、切完长什么样 | 指定纯函数簇作样板（§5.1），并给出验收口径 |

还有一条隐性缺陷：上一版的目标（≤400 行 / ≤25 方法）是**终局形态**，但没区分"存量清理"与"新增约束"。
把终局阈值直接做成硬门禁会让 28 个文件当场红，任何人第一次跑测试就撞墙——这也是它无法落地的原因之一。
正确做法是仓库已有的 `FreezingArchRule` 模式：**冻结存量、只拦新增**。

---

## 3. 度量口径修正：上一版的方法数是错的

上一版的体检命令是 `grep -cE '^\s{4}(public|private|protected)\s'`——这个正则**把字段声明也算成了方法**
（`private final FormDefinitionMapper mapper;` 同样匹配）。实测对比：

| 类 | H7 口径（只数方法） | 上一版口径（含字段） | 虚高 |
| --- | --- | --- | --- |
| `FormDefinitionService` | **76** | 85 | +12% |
| `WecomService` | **63** | 83 | +32% |
| `WorkflowRuntimeV2` | **77** | 81 | +5% |
| `OptionSourceService` | **65** | 76 | +17% |
| `ProcessEngine` | **48** | 63 | +31% |

后果不只是数字难看：**按错误口径排序会排错优先级**。上一版把 `ProcessEngine` 列为第 7 位（63 方法），
实际它只有 48 个方法；而 `OptionSourceService` 实际 65 个，比引擎更该先动。

**统一口径**（已在 `scripts/health-metrics.mjs` 的 `JAVA_METHOD_DECL` 固化）：

```js
const JAVA_METHOD_DECL = /^\s{4}(?:public|private|protected)\s[^=;]*\(/;  // 必须含括号，排除字段
```

### 3.1 真实基线（2026-10-20 实测，已写入 `docs/health-metrics.baseline.json` 的 H7）

| 指标 | 值 |
| --- | --- |
| 后端生产类总数 | 222 |
| >400 行的类 | **16** |
| >25 方法的类 | **19** |
| 最大类 | **1403 行 / 77 方法**（`FormDefinitionService` / `WorkflowRuntimeV2`） |
| 前端+移动端 >600 行生产文件 | **8** |
| 最大文件 | **1250 行**（`FormManagementWizard.tsx`） |

H7 已在 CI 的 `metrics` job 生效：**六个口径只许下降，任一项变大即红**。这一步不依赖任何重构，
今天开始就能阻止债务继续增长。

---

### 3.2 口径二次修正（2026-10-22）：补上包内可见方法

§3 修掉了"字段被算成方法"（虚高），但那个正则只认 `public|private|protected`，**又漏掉了包内可见方法**
（`List<Foo> bar(...)`，没有修饰符）。这不是小数：

| 类 | 修正前 | 修正后 |
| --- | --- | --- |
| `WecomService` | 63 | **77**（+14 个包内 static） |
| `AuthorizationService` | 59 | **67** |
| 新建的 `OptionBindingGuard` | 1 | **9** |

更糟的是它可被利用：**把方法改成包内可见，门禁就"看起来变好"**。已修正为
"4 空格缩进 + 排除控制流关键字 + 可选修饰符/泛型 + 返回类型 + 方法名 + `(`"，并加了两条 `--self-test` 回归断言
（包内可见必须计、`return`/`if` 不得计）。

**基线不用重建**：修正后超 25 方法的类仍是 19 个、最大值仍是 77（`WorkflowRuntimeV2` 与 `WecomService` 并列），
所以 `docs/health-metrics.baseline.json` 的 H7 数值依然成立——变的只是清单里每个类的准确数字。

## 4. 识别：三条信号，命中两条即立项

上一版给了四条，其中"调用者视角混乱"在本仓库几乎必然成立（都是 `@Service` 被 Controller 注入），
区分度低。收敛为三条，都可实测：

1. **规模**：后端 >400 行或 >25 方法；前端/移动端 >600 行。（H7 已自动列出，无需人工判断）
2. **方法簇不共享状态**：把私有方法按"它读了哪些字段"聚类，能切出 ≥2 个几乎不重叠的簇。
   **这是唯一可靠的拆分依据**——它同时证明了"能拆"和"拆完不会互相踩"。
3. **变化原因多于一个**：同一个类，不同类型的需求都要改它。判断方法很土但有效：
   查该类近 20 次提交，若提交信息跨越 ≥2 个业务主题（如"改校验规则"与"改列表分页"），即成立。

**不该拆的**（上一版已列，此处只补充本项目实例）：`FieldTypeCatalog`（同构注册表）、
`FormDefinition` 等 record/DTO、`form/handler/**` 下按控件类型一一对应的处理器（本来就是一个类一个控件）、
`services/antflow/**`（生成物）、`map-grid.ts`（4 万行 mock 数据）。

> 判断"能拆"的快捷实验：在类里随机挑 10 个私有方法，看它们引用的字段集合是否只落在 1–2 个字段上。
> 若是，这 10 个方法大概率构成一个可独立出去的簇。

---

## 5. 粒度与边界：切到"一个变化原因"为止

### 5.1 目标粒度（与上一版一致，但区分"存量"与"新增"）

| 指标 | 终局目标 | 存量处理 |
| --- | --- | --- |
| 生产类行数 | ≤400 | 冻结，只降不升（H7） |
| 单类方法数 | ≤25 | 同上 |
| 单方法行数 | ≤80 | 只对新代码评审时卡 |
| 类构造依赖 | ≤8 | 见 §6.1 实测问题 |
| 单测文件 | ≤800，且按 `@Nested` 分主题 | 存量冻结 |

**下限同样重要**：拆到"一个类只有一个变化原因"就停。再往下会得到只做转发的壳
（`FooService` 里每个方法都只有一行 `return bar.doIt()`），跳转成本反而更高。

### 5.2 四个巨类的真实刀口（按方法簇实测，可直接开工）

#### `FormDefinitionService` 1403 行 / 76 方法

方法名前缀分布已经暴露了簇结构：`validate*` 21 个、`collect*` 6 个、`canonicalize*` 4 个、`project*` 3 个。

| 刀口 | 含哪些方法 | 估算行数 | 是否纯函数 | 建议次序 |
| --- | --- | --- | --- | --- |
| **Schema 校验** | `validateSchema` / `validateNode` / `validateNodeValue`(×2) / `validateValueShape` / `validateTableValue` / `validateMatrix*`(4) / `validateSelect*`(2) / `validateChecklist*` / `validatePublishingNode` / `rejectDisplayConditionCycles`(2) / `collectConditionSources` | **≈700（一半）** | ✅ 只读 schema，无 DB、无事务 | **第 1 刀** |
| **可见性投影** | `projectStarterSchema` / `projectStarterData` / `projectSchemaNode` / `removeHiddenData` / `filterVisibleSubmission` / `visibleNodeIds`(2) | ≈180 | ✅ 纯函数 | 第 2 刀 |
| **数据规范化** | `canonicalizeStarter*`(4) / `collectStarterValues` / `collectStarterTable` / `hasStarterVisibleField` | ≈150 | ✅ 纯函数 | 第 3 刀 |
| **定义 CRUD + 状态机** | `saveDraft` / `update` / `publish` / `disable` / `softDelete` / `list` / `getByCode` | ≈250 | ❌ 持事务 | 留原地 |

三刀切完，原类约 250 行、只剩 CRUD 与状态迁移——**这就是"拆到位"的样子**。
注意 `validateNodeValue` 有两个重载（714 行、832 行），搬迁时必须一起走，否则会留下半套校验。

#### `WorkflowRuntimeV2` 1236 行 / 77 方法

| 刀口 | 内容 | 风险 |
| --- | --- | --- |
| 候选人解析 | `assignments` / `previewAssignments` / `fieldUsers` / `ccUsers` / `fallbackUsers` / `roleUsers` / `activeUsers` | 低（只读） |
| 仲裁决策 | `approve` / `rejectVote` / `rejectAdditional` / `counts` / `branchCounts` / `parallelBranch*` | **高**（写状态） |
| 任务生命周期 | `addParticipant` / `activateAfterSign` / `completeBeforeSign` / `recallApproval` / `adminReassign` / `terminate` / `withdrawToRework` | **高** |
| 超时调度 | `scheduleTimeout` / `scheduleSequentialTimeout` / `timeoutReminder` / `timeoutEscalate` | 中 |

**引擎类不是"先拆"，而是"先锁"**：动它之前必须先补特征测试并定案 V1/V2 唯一写入方
（`health-metrics` H4 显示当前有 2 套可写运行时）。否则拆完仍是两套逻辑并行推进，等于把复杂度挪了个位置。

#### `WecomService` 1313 行 / 63 方法

刀口非常干净，因为它混了**三种失败语义完全不同**的东西：
`saveSettings`（配置，同步、可回滚）、`run`+`sync*`（长任务，有 job 生命周期与租约）、
`config`/`mapped*`/`job`（纯查询与映射）。**不要**合成"微信服务"——同步失败要重试、消息失败要告警、
配置失败要回滚，三者塞在一个类里，任何一处改错都会波及其他两条链路。

#### `OptionSourceService` 1044 行 / 65 方法（2026-10-21 按实测修正）

> 本节原文写的 `fetch*`（外部 HTTP，有超时与 SSRF 约束）与 `resolve*`（运行时缓存）**都与代码不符**：
> `options` 包内零 HTTP 调用（无 RestTemplate/WebClient/HttpClient/URL），运行时解析早在独立的
> `OptionRuntimeService` 里。按实际职责重新划界：

| 刀口 | 内容 | 状态 |
| --- | --- | --- |
| **表格导入** | `readImport` / `parseFile` / `parsePastedText` / `parseDelimited` / `parseXlsx` / `csvRows` / `validateTable` / `rowMap` / `safeName` / `stripBom` + 4 个规模常量 + `ImportedTable` | ✅ 已拆为 `OptionTableImporter`（195 行） |
| **引用校验** | `isReferenced` / `bindingFormCount` / `boundVersionIds` / `collectVersionIds` / `referencedForms` / `requireForms` / `versionUsage` / `versionReferencingForms` / `deleteBlockedReason` | ✅ 已拆为 `OptionBindingGuard`（186 行，只读表单侧表） |
| **配置与版本状态机** | `list` / `detail` / `create` / `delete` / `publish` / `unpublish` / `disable` / `enable` / `replaceGrants` / `replaceForms` / `diffVersions` / 版本读模型 | 留原类，当前 **751 行 / 45 方法** |

**进度**：`OptionSourceService` **1044 行 / 65 方法 → 751 行 / 45 方法**（两刀合计 -293 行 / -20 方法）。

> **剩余部分为什么停在 751**：剩下的全是"配置写入 + 版本状态迁移"，共享同一个事务边界与
> `t_option_data_source.version` 乐观锁。要再降到 ≤400，只能按**用例**拆成多个写服务
> （导入/发布/授权各一个），每个都持有自己的事务——那会从"一个聚合一个写入口"变成"多个写入口"，
> 与 `AGENTS.md` §3 的不变量相冲突。**建议**：先拆出纯读的版本视图/版本对比（约 60 行，风险低），
> 把类压到 ~690；剩下的发布状态机保留为一个聚合，等 FormDefinitionService 那一批做完、有了更多
> 拆分经验后再重新评审划界。

**边界依据**：导入解析只读字节与文本（纯函数，零风险）；引用校验只读 schema 里的引用关系，
但要注意它要跨表读 `t_form_data`——搬走时必须连同"只读、不写"的约束一起带过去。
`OptionSourceService` 是后端风险最低的一个（无事务、无引擎状态），适合做第一批。

### 5.3 前端/移动端：按"视图 / 状态 / 纯逻辑"三分

快捷键在于：**先抽纯函数，再抽 hook，最后才是组件**。以 `FileUploadField.tsx`（845 行）为例：
上传状态机（`idle → uploading → polling → done/failed`）是纯逻辑，可先抽成 `useFileUpload.ts` 并单测；
列表渲染抽成 `UploadList.tsx`；选择器抽成 `UploadPicker.tsx`。三者拆完组件应 ≤150 行。

`location ^~ /mobile/assets/` 的缓存策略（`immutable` 1 年 + `try_files =404`）决定了
**移动端拆分不能随本方案推进**：移动端 `GlobalErrorBoundary` 没有 chunk 失效识别，
重试会再次 import 已删除的文件名。上线前必须先补兜底，详见
[`class-decomposition-review-2026-10-20.md`](class-decomposition-review-2026-10-20.md) §2.2 B-R1。
下面对移动端的拆分设计保留为**目标形态**，不是本迭代的执行项。

`FormManagementWizard.tsx`（1250 行）同理：4 个步骤各一个组件 + `useWizardState.ts` + `wizardValidation.ts`。
**不要**按"UI 组件 vs 工具函数"这种技术维度切——要按步骤（业务维度）切，否则每个步骤仍要跨 3 个文件读。

---

## 6. 拆分后如何保持职责清晰、降低耦合

### 6.1 先看当前耦合的实测问题

`ProcessEngine` 的构造依赖是 **16 个**（14 个 `final` 构造器注入 + 2 个 `@Autowired` 字段注入），是目标值（≤8）的两倍。
这本身就是"职责多"的直接证据，也是拆分的主要收益：依赖数会随职责一起下降。

顺带两个已存在的隐患，拆分时必须一并处理：

- `ProcessEngine` 用**字段注入**（`@Autowired private WorkflowRuntimeV2 runtimeV2`），
  而其他依赖用构造器注入。混用会让"这个类到底依赖谁"在类头看不出来，且字段注入无法用 `final` 保证不可变。
  搬到新类时统一用构造器注入。
- 多个 `@Autowired(required = false)`（`businessNumbers`、`versions`、`optionRuntimeService`）意味着
  **同一份代码在不同装配下走不同分支**。拆出去的类不应继承这种"可选依赖"，否则新类也会有一半路径测不到。

### 6.2 五条硬规则（在上一版基础上按本仓库情况收紧）

1. **事务只在用例层**：`@Transactional` 只标在"完成一个业务动作"的方法上。搬出去的校验器/投影器
   **不加**注解、也不回调事务方法。本仓库已有先例教训：Spring 自调用会让 `@Transactional` 静默失效，
   而这类 bug 在生产上表现为"部分成功"，极难复现。
2. **单向依赖**：`Controller → 用例服务 → 协作者 → Mapper`，协作者之间禁止互调；需要编排时由用例服务编。
3. **参数对象化**：搬方法前若签名 ≥5 个参数，先引入 `record`。这条不只是美观——
   `FormDefinitionService.validateNodeValue(node, values, fieldIds, mode, ...)` 这类签名，
   接手者根本无法在不读完整个方法的情况下知道每个参数的含义。
4. **不共享可变状态**：搬出去的类不得复用原类的缓存/`ThreadLocal`。`WecomService` 里的
   `runningClaims` + `ThreadLocal<Lease>` 是典型：一旦跟着方法搬走，租约语义会散落在两个类里。
5. **一个协作者只碰自己负责的表**：跨表读取走既有 `…QueryService` 或读模型，不在校验器里顺手查库。
   这条是防止"拆完耦合度没降"的关键——`JdbcTemplate` 直查在 `WecomService` 里到处都是，
   搬方法时若连 SQL 一起搬，新类立刻变成第二个巨类。

### 6.3 包结构：跟随现有分包，不新造顶层包

沿用 `com.antflow.<模块>.<角色>` 的既有习惯（如 `form/options`、`engine/tree`、`engine/handler`）。
新增的校验器放 `form/schema`，投影器放 `form/projection`，**不要**建 `com.antflow.util` 或 `com.antflow.common.helper`
——那会变成新的垃圾抽屉。每个新包加 `package-info.java` 写清"负责什么、不负责什么、入口是谁"。

---

## 7. 重构手法：单簇搬迁（绞杀者的最小单元）

上一版的"六步"是对的，但以"类"为单位。改为**以方法簇为单位**，每次一个 PR：

1. **锁行为**：跑该类相关测试存基线。本仓库安全网比想象中好：
   `FormDefinitionServiceSchemaTest`、`ProcessEngine{Automation,Parallel,Tree}Test`、
   `WecomService{Permission,Rules}Test`、`ProcessDefinitionServiceValidationTest` 已存在。
   覆盖不足的簇（如 `OptionSourceService` 的表格导入，2026-10-21 已补 23 个用例）先补特征测试——**把当前行为（含疑似 bug）固定住**。
2. **聚类并命名**：按 §5.2 的刀口，写出目标类名与它要接管的字段清单。
3. **先搬纯函数**：无 DB、无事务的簇先走。这一刀必须做到"编译通过 + 测试全绿 + 零行为变化"。
4. **抽参数对象**：跨簇传递的参数包成 `record`，为第 5 步铺路。
5. **再搬有 I/O 的簇**：一次一簇，搬完立刻跑测试。**一个提交只搬一簇**，提交信息写"搬了什么 / 行为未变"。
6. **收尾**：原类补一段 Javadoc 写清"这个类允许做什么"，并更新 `ArchitectureBoundaryTest` 的白名单。

**红线（违反即失去"只重构"的验证能力）**：不改端点、不改 SQL、不改事务传播、不顺手修 bug、
不重命名公开方法。`ApiContractSnapshotTest` + `frontend/config/antflow-api.json` 逐字不变，
是"只重构"最硬的证据——**拆分期间这个文件若变了，说明动到了接口，PR 应被打回**。

---

## 8. 规范与评审机制

| 机制 | 内容 | 状态 |
| --- | --- | --- |
| **H7 趋势门禁** | 6 个口径只降不升；CI `metrics` job 卡；同时打印"待拆清单前 10" | ✅ **本次已落地** |
| **ArchUnit 体积规则** | 新增类 >400 行 / >25 方法即红；存量用 `FreezingArchRule` 冻结 | ⬜ 待加（下一刀） |
| **PR 检查单** | ①有无新增超标类 ②新类是否单一变化原因 ③`@Transactional` 是否只在用例层 ④`antflow-api.json` 是否意外变化 ⑤测试是否 ≤800 行且有 `@Nested` | ⬜ 待建 |
| **提交纪律** | 一次提交只搬一簇；提交信息含行数/方法数前后对比 | ⬜ 写入 `AGENTS.md` |
| **package-info** | 每个新包说明"负责/不负责/入口" | 随拆分做 |
| **ADR** | 每次大拆分记一条：为什么这么切、否决了哪种切法 | 写入 `docs/DECISIONS.md` |

ArchUnit 规则的写法（复用仓库已有的冻结模式，**不要**新建机制）：

```java
// 只拦新增：存量 16 个超标类冻结在 archunit_store/，新写一个 500 行的类当场红。
ArchRule rule = classes().that().resideInAPackage("com.antflow..")
    .and().areNotInterfaces().and().areNotEnums()
    .should().haveNumberOfLinesLessThanOrEqualTo(400);  // 需自定义 ArchCondition 读字节码行号
FreezingArchRule.freeze(rule).check(CLASSES);
```

> 注意：ArchUnit 没有现成的"行数"条件，需要自定义 `ArchCondition<JavaClass>`（读源码行数或字节码行表）。
> 若嫌麻烦，**H7 已经覆盖了这个需求**——ArchUnit 的增量价值只在"PR 内即时反馈"，
> 优先级低于 PR 模板。不要为了凑门禁而写一个脆弱的自定义规则。

---

## 9. 适用与不适用

**适用**（本仓库命中项打 ✅）：

- ✅ 方法数 >25 且方法可分簇（19 个类）
- ✅ 一个类被管理端配置页与运行时请求路径同时依赖（`OptionSourceService`、`FormDefinitionService`）
- ✅ 改一个需求要在 1000 行里找落点
- ✅ 测试文件比被测代码更难读（`PostgresTransactionalIntegrityIntegrationTest` 5475 行）
- ✅ 新人问"这段逻辑在哪"（接手成本已可感知）

**不适用**：

- 生成物（`services/antflow/**`、`api.generated.d.ts`）、mock 数据、外部参考副本（`ant-design-pro-master/`、`wflow-master/`）
- 同构注册表（`FieldTypeCatalog`）、按控件类型一一对应的 handler、record/DTO
- 只有一个调用方且必然同步变化的内部帮助类——拆了只增加跳转
- **架构分层本身**：本仓库分层是健康的，不要借"可维护性"之名重画包结构

---

## 10. 风险（含本仓库当前的真实风险）

| 风险 | 后果 | 对策 |
| --- | --- | --- |
| **并行开发冲突（当前最高风险）** | 工作区有 **55 个未提交修改**，含 `ProcessEngine`、`WorkflowRuntimeV2`。此时动同文件必然冲突或互相覆盖 | 动某个类前先确认它没有在途改动；按包串行推进，一次只锁一个类 |
| 事务边界被拆散 | 部分成功、数据不一致 | 事务只在用例层；搬出的协作者无事务 |
| Spring 自调用失效 | 注解静默失效 | 搬完禁止同类内部调用带注解方法；编译器与测试都发现不了，只能靠规则 |
| 引擎改坏 | 审批推进错误且难回滚 | 引擎最后动；先定 V1/V2 唯一写入方；先补特征测试 |
| 覆盖率不足时硬拆 | "重构"变"重写" | 覆盖低的簇先补特征测试（`OptionSourceService` 是重点） |
| 顺手改 SQL | 分页/数据权限漂移 | 拆分不碰 SQL；`WecomService` 的 JdbcTemplate 直查尤其危险 |
| 拆出贫血壳 | 类数暴增、跳转成本更高 | 以"一个变化原因"为下限；出现纯转发类即停 |
| 一次大 PR | 评审无法验证行为不变 | 单次 ≤300 行；超了就拆 PR |
| 门禁被绕过 | 指标形同虚设 | H7 的 `--write` 必须与 PR 说明同批出现（改了基线就要解释） |

---

## 11. 执行步骤

**第 0 步（已完成，本次提交）**
H7 进 `health-metrics.mjs` + 基线；CI 自动生效；`--self-test` 覆盖"变大报红 / 拆小不报 / 新增类不报"。

**第 1 步：样板刀（✅ 已完成，2026-10-21）**
拆 `OptionSourceService` 的**表格导入簇** → `OptionTableImporter`（原计划写的 `fetch*` 外部 HTTP 簇不存在，见 §5.2 修正说明）。
选它的理由：纯输入输出、无事务、无引擎状态，且能立刻验证"搬完测试仍绿 + 契约文件不变"这套流程。
**产物是流程，不是代码**——为此补了 `OptionTableImporterTest`（23 个特征用例），并做了"忽略修饰符后 114 行逐字一致"的验证。

**第 2 步：纯函数簇（2–3 天）**
`FormDefinitionService` 的 Schema 校验簇（≈700 行）→ `FormSchemaValidator`；随后投影簇 → `FormSchemaProjector`。
每刀一个提交。

**第 3 步：门禁固化（0.5 天）**
PR 模板 + `AGENTS.md` 提交纪律。此时存量已降，冻结基线同步收紧。
ArchUnit 体积规则**不加**：H7 已覆盖同一目标，而 ArchUnit 没有内置行数条件，需自定义
`ArchCondition` 读字节码行表，收益低于维护成本（见 review §7.2）。

**第 4 步：业务服务（3–5 天）**
`WecomService` 按三种失败语义拆 3–4 个；`MobileWorkflowService` 按查询/命令拆 2 个；
`ProcessDefinitionService` 拆校验器。前端按 §5.3 三分，**但移动端部分暂缓**（P1，见 review §2.2 B-R1）。

**第 5 步：核心域（最后）**
定 V1/V2 唯一写入方 → `WorkflowRuntimeV2` 先拆候选人解析与超时调度（低风险两簇）→ 再拆仲裁与生命周期。
`ProcessEngine` 同理，且必须先补特征测试。

**每步验收**：相关测试全绿；`antflow-api.json` 逐字不变；H7 对应数字下降且不回弹；
`node scripts/health-metrics.mjs` 通过。

---

## 12. 判断"拆到位"的标准

**可测量（CI 可查）**

1. `FormDefinitionService` ≤400 行且 ≤25 方法（当前 1403/76）——它是"是否真的拆了"的试金石。
2. H7 六项全部下降：`maxClassLines` 1403 → ≤600；`backendOverLines` 16 → 0。
3. `node scripts/health-metrics.mjs` 全绿，且基线是**收紧后**重写的（不是靠 `--write` 放水）。
4. `antflow-api.json` 在拆分期间逐字不变。
5. 每个新增类的方法数 ≤25，且没有"只做转发"的类（可用"类内只有一个方法且仅一行委托"识别）。

**可感知（人肉验收，每期一次）**

6. 没参与拆分的人，**30 分钟内**能定位三处逻辑：字段必填校验、驳回回退、存储配置生效点。
7. 改一条业务规则，首次触达文件数 ≤5。
8. 让接手者只看新类的 Javadoc + `package-info`，能说清"这个类不负责什么"。

**反向标准（说明拆过头了）**

- 出现纯转发类；`record` 被拆成多文件；新包没有"不负责什么"的说明；
- 同一处逻辑要在 3 个以上文件间来回看；
- 为了凑 H7 数字把常量/枚举拆散。

---

## 13. 本次实际改动

| 文件 | 改动 |
| --- | --- |
| `scripts/health-metrics.mjs` | 新增 `h7()` / `h7Offenders()` / `isTsSource()`；`compare()` 增 H7 六口径；`--self-test` 增 5 条断言；成功输出增"待拆清单前 10"。复核阶段补 `assertScanComplete`（扫描规模 fail-closed），堵住"目录缺失→计数归零→静默通过"的失效路径 |
| `docs/health-metrics.baseline.json` | 新增 H7 实测基线（222 类 / 16 超标 / 19 超方法 / 1403 行 / 77 方法 / 8 前端文件 / 1250 行） |
| `docs/DECISIONS.md` | 新增 `D-20261020-class-size-gate-before-split`：记录"门禁先于拆分"与口径统一 |
| `docs/class-decomposition-execution-2026-10-20.md` | 本文件 |

**未改动**：任何业务代码、任何测试、`ci.yml`（当前有在途修改，避免冲突）。
