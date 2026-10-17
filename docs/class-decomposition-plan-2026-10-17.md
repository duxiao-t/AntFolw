# 臃肿类拆分与可维护性提升方案

日期：2026-10-17 ｜ 范围：`backend/`、`frontend/`、`mobile/` 的自有源码
性质：**只讨论类级分解与工程机制，不改动运行时行为**；与 `Engineering discipline.md`、`AGENTS.md` §8 的既有纪律衔接。

## 0. 实测基线：先把"痛"量化

> 先纠正一个前提：生产代码里**没有**"几千行"的类。实测最大生产类是 1403 行；真正超过 5000 行的单体在测试目录。
> 但你的体感是对的——**方法数**才是问题：7 个类的方法数超过 60，最多的 85 个。一个人无法在脑中容纳 85 个方法的类。

### 0.1 后端：方法数超标类（这才是真实债务）

| 方法数 | 行数 | 类 | 职责现状（读注释得出） |
| --- | --- | --- | --- |
| 85 | 1403 | `form/FormDefinitionService` | 定义 CRUD + 草稿/发布状态机 + schema 校验（多入口）+ ROOT 字段权限 + schema 裁剪 + 叶子字段收集 |
| 83 | 1313 | `integration/wecom/WecomService` | 应用配置 + 通讯录同步 + 部门/用户映射 + 消息投递 + 免登 |
| 78 | 1148 | `engine/WorkflowRuntimeV2` | V2 运行时：推进/仲裁/驳回/自动化桥接混在一起 |
| 76 | 1044 | `form/options/OptionSourceService` | 选项源 CRUD + 运行时解析 + 外部 HTTP 拉取 + 引用校验 |
| 75 | 811 | `mobile/workflow/MobileWorkflowService` | 移动端待办/我发起/已处理/详情/驳回重提的查询与命令混合 |
| 64 | 809 | `authz/AuthorizationService` | 三层鉴权（端点能力/资源授权/数据范围）+ 版本缓存 |
| 63 | 1258 | `engine/ProcessEngine` | V1 引擎：树遍历 + AND/OR 仲裁 + 驳回回退 + 自动化 |
| 58 | 1018 | `process/ProcessDefinitionService` | 定义 CRUD + 树校验 + 发布版本 |

次要（50–60 方法 / 500–800 行）：`MobileFileService`、`UserService`、`OptionRuntimeService`、`RoleAdminService`、`FormGrantService`。

### 0.2 单体测试类（接手者最难读的地方）

| 行数 | 类 | 说明 |
| --- | --- | --- |
| **5475** | `backend/src/test/.../PostgresTransactionalIntegrityIntegrationTest` | 一个类覆盖全部事务不变量，几百个断言平铺 |
| 1025 | `mobile/.../advanced-fields.test.tsx` | 字段测试全家桶 |
| 914 | `mobile/.../submit-flow.test.tsx` | 提交流程全家桶 |

### 0.3 前端 / 移动端

| 行数 | 文件 | 问题 |
| --- | --- | --- |
| 1250 | `frontend/src/pages/approval/FormManagementWizard.tsx` | 向导的 4 个步骤、校验、提交、预览挤在一个组件 |
| 965 + 600 | `frontend/src/pages/proc/{Detail.tsx,detailPresentation.ts}` | 详情页视图与展示逻辑双胖 |
| 750 | `frontend/src/pages/org/Contacts.tsx` | 通讯录表格 + 选择器 + 导入导出同文件 |
| 848 / 845 | `mobile/.../ApprovalRecords.tsx`、`FileUploadField.tsx` | 列表渲染与上传状态机各自单点膨胀 |

### 0.4 明确**不参与**统计的部分（避免误启动重构）

- `frontend/src/pages/dashboard/monitor/mock/map-grid.ts`（41k 行）、`map-geo.ts`（7.4k 行）：**mock 地图数据**，不是代码；
- `frontend/src/services/antflow/**`、`shared/api.generated.d.ts`：**生成物**，禁手改；
- `ant-design-pro-master/`、`wflow-master/`：外部参考副本，不入库、不重构。

---

## 1. 如何识别：四类信号 + 量化阈值

**一条类"该拆"的证据链**（命中 ≥2 条即立项）：

1. **规模**：生产类 >400 行 或 >25 个方法；单方法 >80 行；构造参数 >8 个。
2. **变化原因多于一个**：同一类里，不同方法的改动常来自不同需求（例如"改校验规则"和"改列表接口"都要动它）。
3. **方法簇之间不共享状态**：把私有方法按"引用了哪些字段"聚类，能分出 ≥2 个几乎不重叠的簇——这是最可靠的拆分信号（本项目的 7 个超标类都符合）。
4. **调用者视角混乱**：一个类同时被"管理端配置页"和"运行时请求路径"调用，而两组方法互不相干（如 `OptionSourceService`）。

**一次性体检命令**（可固化成脚本）：

```bash
# 后端：方法数 >60 或行数 >600 的类
for f in $(find backend/src/main/java -name '*.java'); do
  m=$(grep -cE '^\s{4}(public|private|protected)\s' "$f"); l=$(wc -l < "$f")
  [ "$m" -gt 60 -o "$l" -gt 600 ] && printf '%3s 方法 %5s 行 %s\n' "$m" "$l" "$f"
done | sort -rn

# 前端/移动端：>600 行的非生成物
find frontend/src mobile/src -name '*.ts*' | grep -vE '\.umi|services/antflow' | xargs wc -l | awk '$1>600 && $2!="total"'
```

**反例（不要拆）**：纯 DTO/值对象、枚举与常量表、生成物、mock/夹具数据、稳定不动且方法虽多但同构的类（如字段注册表的机械注册）。

---

## 2. 拆分的粒度与边界

### 2.1 四条划界判据（按优先级）

| 判据 | 含义 | 落点 |
| --- | --- | --- |
| **变化原因** | 会因同一个需求一起改的代码放一起；不同需求改的拆开 | 命令 vs 查询、配置 vs 运行 |
| **数据所有权** | 谁负责写某张表/某个状态，谁才持有那份逻辑 | 一个聚合根一个写入口 |
| **事务边界** | 一次事务内的完整业务动作 = 一个用例方法 | 见 §4.1 的事务规则 |
| **调用者视角** | 面向不同调用者（管理端/运行时/移动端）的入口分开 | Controller → 用例服务 |

### 2.2 目标粒度（写进门禁，见 §6）

| 指标 | 目标 | 理由 |
| --- | --- | --- |
| 生产类行数 | ≤400 | 一屏能读完结构 |
| 单类方法数 | ≤25 | 超出则人脑无法同时保持上下文 |
| 单方法行数 | ≤80 | 超出往往意味着多层嵌套 |
| 类构造依赖 | ≤8 | 依赖多通常意味着职责多 |
| 单测文件行数 | ≤800，且按 `@Nested` 分主题 | 测试是"接手说明书" |

> 粒度不是越细越好：**拆到"一个类只有一个变化原因"为止**，再拆就会得到一堆贫血的转发类（getter 壳），反而更难读。

---

## 3. 七类超标类的具体拆分方案

### 3.1 `FormDefinitionService`（1403/85）→ 6 个类

| 目标类 | 职责 | 从现类搬走什么 |
| --- | --- | --- |
| `FormDefinitionRepository` | 定义的读写与批量取（替代逐条 `getById`） | 所有直接 `mapper.*` 的调用 |
| `FormDefinitionCommandService` | 创建/更新/删除 + **草稿↔发布状态迁移规则** | `update` 的"字段为 null 保持原值""只有 schema/settings 变才降级草稿"等分支 |
| `FormSchemaValidator` | 结构校验（环检测、必填、类型、可引用性） | 各校验入口的公共部分 |
| `FormFieldPermissionResolver` | ROOT / 节点级 `formPerms` → 可见性与可编辑性 | `configuringFields`、`leafFieldIdToType` 等 |
| `FormSchemaProjector` | 按权限裁剪 schema / data 响应 | `sanitized*` 系列方法 |
| `FormPublishService` | 发布快照与版本 | 发布相关方法 |

**边界依据**：校验只读 schema（无副作用）→ 可独立；投影是纯函数（最适合先拆，风险最低）；命令服务持有事务。

### 3.2 `WecomService`（1313/83）→ 5 个类

`WecomConfigService`（应用配置与凭证）、`WecomDirectorySyncService`（同步主流程 + job 状态）、`WecomMappingService`（部门/用户映射）、`WecomMessageService`（消息投递与重试）、`WecomSsoService`（免登与 JS-SDK 签名）。
**边界依据**：同步是长任务（有 job 生命周期），消息投递是异步外呼，二者失败语义完全不同——**不要**合成"微信服务"。

### 3.3 `ProcessEngine`(1258/63) + `WorkflowRuntimeV2`(1148/78) → 各自的 4 个协作者

> 这是**核心域、风险最高**，务必最后动，且先固化行为（见 §4.2）。

`TreeNavigator`（树遍历与后继解析，已有 `engine.tree` 可扩）、`ApprovalDecider`（AND 会签/OR 或签的仲裁）、`RejectionPolicy`（驳回、回退上一级、REWORK 原单重提）、`AutomationBridge`（DELAY/TRIGGER 与 `t_workflow_job` 的桥接）。
**前置条件**：先明确 V1/V2 谁是唯一写入方（`health-metrics` H4 已在跟踪"可写运行时实现数"），否则拆完仍有两套并行推进逻辑。

### 3.4 `OptionSourceService`(1044/76) → 4 个类

`OptionSourceAdminService`（配置 CRUD）、`OptionRuntimeResolver`（运行时解析与缓存）、`OptionSourceHttpClient`（外部拉取，隔离网络与超时）、`OptionBindingGuard`（引用校验，防"被撤销引用后仍保存"）。

### 3.5 `MobileWorkflowService`(811/75) → 3 个类

`MobileTaskQueryService`（待办/我发起/已处理/详情的**查询**，含分页与权限过滤）、`MobileTaskCommandService`（同意/驳回/重提的**命令**）、`MobileTaskPresenter`（DTO 组装，防 N+1）。
**边界依据**：查询与命令的失败语义、事务边界、缓存策略都不同。

### 3.6 `AuthorizationService`(809/64) → 4 个类

`CapabilityChecker`（能力码判定）、`ResourceGrantResolver`（资源级授权）、`DataScopeResolver`（数据范围）、`AuthzVersionCache`（版本与失效）。
**边界依据**：三层鉴权本就对应三个问题空间；缓存是横切关注点，单独放才能被独立测试。

### 3.7 `ProcessDefinitionService`(1018/58) → 3 个类

`ProcessDefinitionAdminService`（CRUD）、`ProcessTreeValidator`（树结构校验）、`ProcessPublishService`（发布与版本快照）。

### 3.8 前端 / 移动端 / 测试

| 现状 | 拆分 |
| --- | --- |
| `FormManagementWizard.tsx` 1250 | `steps/{Basic,Fields,Process,Publish}Step.tsx` + `useWizardState.ts` + `wizardValidation.ts` |
| `proc/Detail.tsx` 965 + `detailPresentation.ts` 600 | `DetailView.tsx`（薄视图）+ `useProcessDetail.ts`（数据）+ `presenters/{summary,timeline,attachments}.ts` |
| `org/Contacts.tsx` 750 | `ContactsTable.tsx` + `UserPickerDrawer.tsx` + `useContactsImport.ts` |
| `mobile FileUploadField` 845 | `useFileUpload.ts`（状态机）+ `UploadList.tsx` + `UploadPicker.tsx` |
| `mobile ApprovalRecords` 848 | `ApprovalRecordCard.tsx` + `groupRecords.ts` |
| `PostgresTransactionalIntegrityIntegrationTest` **5475** | 按不变量主题拆为 `@Nested`（或独立类）：实例推进 / 驳回与重提 / 乐观锁 / 事务消息 / 附件一致性，每块 ≤600 行 |

---

## 4. 拆分后如何保持职责清晰、降低耦合

### 4.1 五条硬规则

1. **事务只放在用例层**：`@Transactional` 只标在 Command/用例服务的方法上；被拆出去的协作者（校验器、投影器）**不加**事务，也不反向调用事务方法——避免 Spring 自调用代理失效的经典坑。
2. **单向依赖**：`Controller → 用例服务 → （校验器/投影器/仓储）→ Mapper`。禁止协作者之间互相回调；确需编排时，由用例服务做编排方。
3. **参数对象化**：拆出的方法若需要 ≥5 个参数（`FormDefinitionService` 里已有这种签名），先引入 `record XxxCommand`，再搬方法——**签名的可读性是拆分的第一收益**。
4. **不共享可变状态**：拆出的类不得复用原类的可变字段（如缓存 map）。需要共享的不可变数据用 record 传参；需要缓存的单独建带失效语义的组件。
5. **Mapper 随聚合走**：一个协作者只碰自己负责的表的 mapper；跨表读取走既有 `…QueryService` 或专用读模型，不在校验器里顺手查库。

### 4.2 行为不变的证据（先建安全网，再动刀）

- **有测试的部分**：先跑绿（`mvn -B -f backend/pom.xml test` 对应类 / `vitest run <file>`），拆完必须仍为同一组测试全绿。
- **没测试的部分（引擎/Wecom）**：先补**特征测试**（characterization test）——把当前行为（含"看起来是 bug 的行为"）用断言固定下来，再重构。禁止"边重构边改行为"。
- **契约不变**：`ApiContractSnapshotTest` 是现成的金丝雀——只重构不改行为时，`frontend/config/antflow-api.json` 应当**逐字不变**（本文所有拆分都不新增/修改端点）。
- **架构边界**：`ArchitectureBoundaryTest`（ArchUnit）已在 CI 中，拆分类不应让它变红。

---

## 5. 重构手段：绞杀者式六步（每次只拆一个类）

1. **量化现状**：记录该类行数/方法数，跑一遍相关测试存基线。
2. **聚类**：按"引用字段/引用表/调用者"给私有方法分组，写出组名（就是未来的类名）。
3. **先搬纯函数**：无副作用、无 DB 的组先搬到新类（`FormSchemaProjector`、`ProcessTreeValidator` 这类），编译+测试必须立刻绿。
4. **抽参数对象**：把跨组传递的参数包成 `record`，为下一步铺路。
5. **搬有 I/O 的组**：一次一组，搬完立刻跑测试；一次提交只搬一组，提交信息写清"搬了什么、行为未变"。
6. **收尾**：原类只剩编排逻辑（理想 ≤200 行）；补一个"这个类允许做什么"的 Javadoc 顶部契约段；把新类登记进架构测试的白名单/包规则。

**禁止**：为了"看起来干净"重命名公开端点、改 SQL、改事务传播、顺手改 bug——这些都会让"只重构"的验证失效。

---

## 6. 规范与评审机制（让后来者扛得住）

| 机制 | 内容 | 落点 |
| --- | --- | --- |
| **门禁（自动）** | ArchUnit 新增：生产类 ≤400 行、方法数 ≤25、方法 ≤80 行、禁止协作者反向依赖 | `backend/src/test/java/com/antflow/ArchitectureBoundaryTest.java` |
| **健康指标（趋势）** | `scripts/health-metrics.mjs` 增 H7：超大文件数 = count(>600 行非生成物)，**只允许下降** | 与既有 H1–H6 同机制，CI 的 `metrics` job 卡 |
| **提交纪律** | 一次提交只做"一个类的一次搬迁"；提交信息含"行数/方法数前后对比" | 与 `AGENTS.md` §9 一致 |
| **PR 检查单** | ①是否有类超过阈值 ②新类是否单一变化原因 ③事务注解是否只在用例层 ④契约文件是否意外变化 ⑤新测试文件是否 ≤800 行且有 `@Nested` 主题 | 新增 `.github/PULL_REQUEST_TEMPLATE.md` |
| **接手文档** | 每个拆出的包加 `package-info.java`：说明"这个包负责什么、不负责什么、入口类是谁" | 后端包级 |
| **ADR** | 每次大拆分写一条决策记录（为什么这么切、被否决的切法） | `docs/DECISIONS.md`（既有） |
| **接手验证（可测）** | 请没参与的人完成"30 分钟内找到 X 逻辑"的任务；做不到就说明结构仍不友好 | 每期拆分后做一次 |

---

## 7. 适用场景

**该用这套方案的场景**：类方法数 >60 且方法可分簇；一个类被两个以上不同调用方使用；改一个需求要读 1000+ 行；新人常问"这段逻辑到底在哪"；测试文件比被测代码还难读。

**不适用的场景**：生成物与 mock 数据（如 `map-grid.ts`）；纯 DTO/枚举；稳定不动且方法同构的注册表；只有一个调用方且必然同步变化的内部帮助类（拆了反而增加跳转）。

---

## 8. 注意事项与风险

| 风险 | 后果 | 对策 |
| --- | --- | --- |
| 事务边界被拆散 | 部分成功、数据不一致 | 事务只在用例层；拆出的协作者保持无事务、纯输入输出 |
| Spring 自调用失效 | `@Transactional`/`@Cacheable` 静默失效 | 拆分后禁止同类内部调用带注解的方法；需要时经注入的协作者调用 |
| 核心域（引擎）改坏 | 审批推进错误，且难回滚 | 引擎放最后；先补特征测试；V1/V2 唯一写入方先定案 |
| 测试覆盖不足时强行拆 | "重构"变成"重写" | 覆盖率低的模块先补特征测试，再动 |
| MyBatis/SQL 被顺手改 | 分页/权限过滤行为漂移 | 拆分不碰 SQL；拆 mapper 时用集成测试比对结果集 |
| 拆过头产生贫血壳 | 类数暴增，跳转成本更高 | 以"一个变化原因"为下限；禁止纯转发类 |
| 一次性大 PR | 评审无法验证行为不变 | 一次一个类、一组一提交；超过 400 行改动的 PR 拆开 |
| 与并行开发冲突 | 合并冲突、互相覆盖 | 先冻结该类一周的改动；或按包分批（本项目当前存在并行会话，尤其要注意） |

---

## 9. 可落地执行步骤

**第 0 步（半天）**：把 §1 的体检命令写成 `scripts/class-size-report.mjs`，输出当前基线表并纳入 CI 只读报告。

**第一期：低风险、见效快（1–2 天）**
前端 `FormManagementWizard` / `proc/Detail`+`detailPresentation` / `org/Contacts`；
后端 `OptionSourceService` → 4 类、`AuthorizationService` → 4 类；
测试 `PostgresTransactionalIntegrityIntegrationTest` → 按主题拆 `@Nested`。
**验收**：CI 全绿；`antflow-api.json` 逐字不变；上述文件均低于阈值；H7 基线建立。

**第二期：业务服务（3–5 天）**
`WecomService` → 5 类、`MobileWorkflowService` → 3 类、`ProcessDefinitionService` → 3 类、`FormDefinitionService` → 6 类。
**验收**：每个类一次提交；每类拆完即跑对应测试；H7 下降；新增 PR 模板生效。

**第三期：核心域 + 固化（3–5 天）**
先定 V1/V2 唯一写入方 → 拆 `ProcessEngine`/`WorkflowRuntimeV2` 的 4 组协作者 → 把 §6 的 ArchUnit 规则与 H7 门禁**转成硬门禁**。
**验收**：行为不变（同一组引擎测试 + 契约快照 + 真实 PG 集成测试全绿）；门禁开启后无人为绕过。

**每期结束做一次 §6 的"接手验证"**，结果写进 `docs/`（谁问、多久找到、卡在哪）。

---

## 10. 判断"拆到位"的标准

**可测量（CI 可查）**
1. 最大生产类 ≤400 行且 ≤25 方法（当前 1403/85）。
2. 无生产类被两个以上不同调用方集（管理端/运行时/移动端）同时依赖。
3. `ArchitectureBoundaryTest` 全绿，且新增的四条体积规则无一豁免。
4. H7「超大文件数」单调下降且不回弹。
5. `antflow-api.json` 在拆分期间逐字不变（证明只重构不改行为）。
6. 单测文件 ≤800 行；每个测试类有可见的 `@Nested` 主题结构。

**可感知（人肉验收）**
7. 陌生开发者 **30 分钟内**能找到"字段必填校验""驳回回退""存储配置生效点"三处逻辑的落点，且无需通读任何单文件。
8. 修改一个业务规则时，首次触达文件数 ≤5。

**反向标准（说明拆过头了）**：出现只做转发的类；为了凑指标把 `record` 拆成多文件；新增包没有任何"它不负责什么"的说明；同一处逻辑仍要在 3 个文件间来回看。
