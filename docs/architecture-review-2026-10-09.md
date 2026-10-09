# AntFlow 架构审计与分模块技术选型（2026-10-09）

**结论：总体方向合理，但不宜照原方案直接执行。** 保留模块化单体、树形审批、独立移动应用和逐步收敛字段语义；把“设计器唯一一次 big-bang”改为有边界的渐进替换。已证实的字段缺陷应先修，多组织归属规则应先决策，不能都排在后续结构重构之后。依据和修订见 Part A、Part B。

## 报告约定

本报告为只读架构审计：只新增本文，并在 `docs/HANDOFF.md` 顶部添加三行指针；不修改代码、配置、迁移、测试、`CLAUDE.md` 或架构决策。Part B 是建议路线，不表示已经实施或已经成为 accepted 决策。

审计基线为 `master`，HEAD `a8c6790152fde216763f0c56cf8114a8a71cb121`。审计开始时工作区已有“数据导出入口迁移”等未提交改动，包括 HANDOFF、DECISIONS、前端页面和未跟踪的 V53；均保留。下列行号及统计取 **2026-10-09 当前工作树**，不能当成生产部署版本证明。已废弃计划中的开 PR、推送、合并不属于本轮任务。 因工作区起初不干净，验收以“相对开始时的差异只有两份指定文档”为准；整仓 git diff --stat 仍会显示用户先前的 src 改动，不能据此认定本轮改了代码。

| 置信度 | 含义 | 本报告的使用方式 |
| --- | --- | --- |
| **已复核** | 本轮直接阅读源码，或执行可复现的只读统计/纯函数检查 | 写明 `path:line`；复现结果与源码事实分别说明 |
| **推断** | 基于已复核事实的风险推导、架构取舍或成本估算 | 不把“建议”写成“系统已经如此” |
| **待实测** | 需要生产配置、数据库、浏览器、负载或部署证据 | 不以源码检查冒充生产验证 |

前提来自本轮需求：企业内多组织、逻辑隔离；未来 6–12 个月同时推进复杂表单/审批规则、外部集成、报表分析、对外开放或移动深化；可以接受单模块重写。没有提供并发量、实例保留量、可用性目标和团队人数，因此不据此承诺容量，也不假设必须多副本部署。报告条目包含性质、影响、动作、代价和置信度；代价统一为单名熟悉项目工程师的工作日估算，属于推断。

## Part A：现状审计

### A1. 核心复杂度与量级

**[推断] 核心复杂度在跨层语义一致性和演化兼容性。** 并行会签、驳回重提、历史版本、权限正交本身确实复杂；额外负担来自两条运行时路径、多份字段解释器和模糊的模块写入边界。尚不能称为“必须全盘推倒”的代码库：版本表、事务消息、页面注册、真库并发测试都已有可复用基础。证据：`backend/src/main/java/com/antflow/engine/ProcessEngine.java:229`、`backend/src/main/java/com/antflow/engine/WorkflowRuntimeV2.java:431`、`backend/src/main/resources/db/migration/V31__workflow_v2_foundation.sql:4`、`backend/src/test/java/com/antflow/integration/PostgresTransactionalIntegrityIntegrationTest.java:1456`。

| 基线项 | 本轮值与置信度 | 统计边界及证据锚点 |
| --- | --- | --- |
| 后端主源码 | **[已复核] 213 个 Java 文件、28,808 物理行** | `backend/src/main/java`；包含注释和空行，不含测试。锚点：`backend/src/main/java/com/antflow/engine/ProcessEngine.java:1` |
| 桌面 pages | **[已复核] 250 个 TS/TSX 文件、82,985 物理行** | `frontend/src/pages`，包括测试和 mock。地图数据 41,411 + 7,383 = **48,794** 行；扣除后 **34,191** 行仍含测试，不能称为纯生产逻辑 LOC。锚点：`frontend/src/pages/dashboard/monitor/mock/map-grid.ts:1`、`frontend/src/pages/dashboard/monitor/mock/map-geo.ts:1` |
| 移动端 | **[已复核] 161 个 TS/TSX 文件、23,826 物理行** | `mobile/src`，包括测试；锚点：`mobile/src/features/forms/schema/fieldRegistry.ts:121` |
| 数据结构 | **[已复核] 迁移源码累计声明 55 个不同表名、5 个不同视图名** | 扫描 `CREATE TABLE` / `CREATE [OR REPLACE] VIEW` 去重；不是实库 introspection。锚点：`backend/src/main/resources/db/migration/V1__init.sql:15`、`backend/src/main/resources/db/migration/V49__ledger_row_version_and_options.sql:15` |
| 权限码 | **[已复核] 50 个 `public static final String` 常量** | 代码目录口径，不等于任一数据库当前授予数；`backend/src/main/java/com/antflow/authz/PermissionCodes.java:1` |
| 大类与大测试 | **[已复核] 六个主类超过 1,000 行；一个真库集成测试文件 4,148 行** | WecomService 1,241；ProcessEngine 1,228；FormDefinitionService 1,217；WorkflowRuntimeV2 1,089；OptionSourceService 1,044；ProcessDefinitionService 1,018。见这些同名 Java 文件及 `backend/src/test/java/com/antflow/integration/PostgresTransactionalIntegrityIntegrationTest.java:1`；行数仅提示阅读成本，不单独证明应拆分 |

**[已复核] 文档基线存在滞后。** `docs/PROJECT.md:26` 仍称当前迁移为 V49，当前树已有 `backend/src/main/resources/db/migration/V52__drop_unused_option_row_gin_index.sql:1`，另有用户既存的 V53。`CLAUDE.md:61` 的引擎说明也未覆盖 WorkflowRuntimeV2。此处只记录差异，按本轮边界不修改这些文件；生产实际 Flyway 版本为 **[待实测]**。

### A2. 已有的正确边界，应保留

| 事实与置信度 | 证据 | 对演化路线的含义 |
| --- | --- | --- |
| **[已复核] 页面信息由注册表与能力清单派生，避免各入口各自维护菜单逻辑** | `frontend/src/pages/registry.ts:1`、`:11`；`frontend/config/page-capabilities.json:1` | 保留。“加页面只改两处”只能指两处元数据，不能把页面组件、菜单数据、测试等也算作零成本 |
| **[已复核] 字段渲染已有数据化注册表及 ConfigPanel 接口** | `frontend/src/registry/formRegistry.ts:24`、`frontend/src/registry/types.ts:39`、`frontend/src/components/form-fields/DeptPickerField.tsx:92` | 应接通既有扩展点；不是从零发明注册系统 |
| **[已复核] 发起路径中业务写入与 V2 outbox 写入同属本地事务** | `backend/src/main/java/com/antflow/engine/ProcessEngine.java:85`、`:111`、`:143`；`backend/src/main/java/com/antflow/engine/WorkflowRuntimeV2.java:431` | 保留事务边界。并非所有通知路径都已走 outbox，见 A7 |
| **[已复核] V31 提供表单/流程版本快照，显式标记近似历史；后续修正采用前滚迁移** | `backend/src/main/resources/db/migration/V31__workflow_v2_foundation.sql:4`、`:17`、`:31`；`backend/src/main/resources/db/migration/V49__ledger_row_version_and_options.sql:1`；`backend/src/main/resources/db/migration/V52__drop_unused_option_row_gin_index.sql:1` | 保留历史版本，不改旧迁移。已抽查修正迁移的理由，未逐份证明“每一条迁移都有充分理由” |
| **[已复核] 移动包体积已有 CI 门禁** | `mobile/scripts/check-bundle-budget.mjs:4`、`:18`；`.github/workflows/ci.yml:95` | 初始入口 JS gzip 总量 ≤160 KiB，单个异步 JS chunk gzip ≤180 KiB；不是“整个应用 ≤180 KiB”。本轮没有测产物实际大小 |

### A3. 字段语义漂移：两项原证据成立，标准答案本身也需校验

**A3-1 [已复核｜正确性缺陷] 声明顺序改变移动端可见性。** 桌面已经递归解析条件来源，移动仍按遍历顺序查询尚未构建完成的集合。证据：`frontend/src/registry/displayConditions.ts:193`、`:209`；`mobile/src/features/forms/schema/validators.ts:114`、`:120`。受影响的是先声明依赖字段、后声明条件来源的表单；移动可能隐藏应填写字段。代价：先建立复现与预期，再修共享语义对应实现，约 1–2 工作日，属于 P0 缺陷修复而非等待 P2 的整批替换。

本轮直接载入两份源码，使用 Node 的 TypeScript 类型擦除在内存执行，无仓库测试改动：

```text
schema = [dependent(displayCondition: source == "yes"), source]
values = { source: "yes" }
桌面 visibleNodeIds       → [dependent, source]
移动 visibleNodeIds       → [source]
只颠倒 schema 声明顺序后  → 两端均包含 source、dependent
```

**[待实测] 生产覆盖范围。** 用户反馈生产已经出现不一致；本轮复现证明当前工作树的算法缺陷，没有访问生产表单或核对线上镜像，不能给出受影响表单数量。

**A3-2 [已复核｜兼容性缺陷] 类型清单 21 / 26 / 26，桌面无未知类型提示。** 桌面缺 `radio / checkbox / search / time / switch`；后端允许、移动可渲染，桌面直接返回空。证据：`frontend/src/registry/formRegistry.ts:24`；`mobile/src/features/forms/schema/fieldRegistry.ts:121`；`backend/src/main/java/com/antflow/form/FormDefinitionService.java:38`；`frontend/src/components/FormRenderer/FormRenderer.tsx:345`。准确的静默返回行为在 **346 行**。影响是导入或其他入口创建的合法 schema 在桌面显示空洞。先明确未知类型提示和是否允许编辑保存，再逐类型补齐；不能只生成一个 union 就认为解决了渲染缺失。预计 2–4 工作日，历史 schema 读写回归另计。

**A3-3 [已复核｜本轮新增复现] 桌面互指条件会递归溢出。** `frontend/src/registry/displayConditions.ts:213` 使用 `!visiting.add(id)`；JavaScript 的 Set.add 返回集合，不能像 Java 一样据此判断是否首次加入。A 依赖 B、B 依赖 A 且值都满足时，本轮执行得到 `RangeError: Maximum call stack size exceeded`。后端对应代码 `backend/src/main/java/com/antflow/form/FormDefinitionService.java:1122` 的 Java Set.add 确实返回布尔值。界面或导入是否能把该输入带入线上渲染为 **[待实测]**。动作：把互指、缺失来源、前向引用都列为规范 fixture，不把桌面当前结果无条件当作 golden；预计与 A3-1 同一修复批次完成。

**A3-4 [已复核｜边界不同] 四份语义实现存在，但职责并不完全相同。** 三端处理 schema/值，SQL 主要解释 BI 字段与显示口径；并非四份完整表单引擎。`table_list` 的行内 children 与 `checklist` 的检查项规则也不应强塞进同一种数据递归。证据：`frontend/src/registry/displayConditions.ts:75`、`:113`；`mobile/src/features/forms/schema/fieldRegistry.ts:168`、`:180`；`backend/src/main/java/com/antflow/form/FormDefinitionService.java:772`、`:1047`；`backend/src/main/resources/db/migration/V49__ledger_row_version_and_options.sql:94`。动作：规范遍历上下文、行作用域和各字段值形状，再收敛适配器；代价归入 P2。

### A4. 桌面设计器：需要收敛，推翻重写的论证不足

| 判定与置信度 | 源码证据 | 对原方案的修订 |
| --- | --- | --- |
| **[已复核] Inspector 1,409 行，switch 集中维护字段配置** | `frontend/src/pages/designer/form/Inspector.tsx:576` | 配置职责集中是真的；应逐类型提取配置面板并接注册分派 |
| **[已复核] ConfigPanel 已存在，但 Inspector 仍使用集中 switch** | `frontend/src/registry/types.ts:45`；`frontend/src/components/form-fields/TextField.tsx:11`；`frontend/src/components/form-fields/DeptPickerField.tsx:92`；`frontend/src/pages/designer/form/Inspector.tsx:576` | 首先核对旧面板与 Inspector 的功能差异，再迁移完整行为；不能直接切过去丢掉新配置项 |
| **[已复核] 所列“真实循环依赖”证据不成立** | `frontend/src/registry/formRegistry.ts:12` → `frontend/src/components/form-fields/DeptPickerField.tsx:5` → `frontend/src/registry/displayConditions.ts:1` | 最后一处只有类型导入，没有返回 formRegistry 的运行时边。这证明目录职责交错，不证明该链成环；其他路径是否成环须导入图检查 |
| **[已复核] 同名 FormDefinition 独立声明为 5 份，另有一个派生 Detail 类型** | `frontend/src/pages/admin/FormList/index.tsx:13`；`frontend/src/pages/approval/FormManagementList.tsx:18`、`:28`；`frontend/src/pages/approval/FormManagementWizard.tsx:49`；`frontend/src/pages/designer/form/FormDesigner.tsx:236`；`frontend/src/pages/designer/process/ProcessDesigner.tsx:55` | “6 份”把派生类型也算作独立副本。应复用生成契约，列表/详情投影仍可保留 |
| **[已复核] API 请求分散；“45 文件”只是字面 request( 的统计** | 例：`frontend/src/pages/designer/form/FormDesigner.tsx:391`、`:409`；`frontend/src/pages/designer/form/Inspector.tsx:26` | 当前树字面调用命中 45 文件，包含泛型调用的文本检索命中 71，直接导入 Umi request 命中 70。不同口径不能混用；这些数字不是 70 个独立 HTTP 栈 |

**[已复核｜未接通的扩展点] FieldType.validate 目前未进入通用渲染校验链。** `frontend/src/registry/types.ts:46` 声明可选 validate，`frontend/src/components/form-fields/AudioUploadField.tsx:154` 有实现；全量 TS/TSX 检索未见该属性调用。迁移注册分派时应先明确统一校验入口，再保留或删除这个钩子，不能误认为“字段对象声明了 validate 就一定会执行”。代价计入 P1/P2。

**A4-1 [已复核｜原方案关键前提错误] OpenAPI 插件已安装，但业务契约没有因此“接通”。** `frontend/config/config.ts:217` 安装插件，`:229` 指向本地 `oneapi.json`；`frontend/config/oneapi.json:4` 的标题为 Ant Design Pro，`:16` 起是模板 API。后端表单实体的 schema 为 String，保存 DTO 使用 Object，不能靠现有 DTO codegen 自动推导 26 类字段的完整判别联合。证据：`backend/src/main/java/com/antflow/form/FormDefinition.java:15`、`backend/src/main/java/com/antflow/form/FormDefinitionController.java:152`。建议复用现有生成工具，但先接入实际后端契约，并单独定义字段语义 spec；预计 3–5 工作日完成最小生成链路，归入 P0。

**A4-2 [推断｜选型] 不换 Umi Max / antd，但也不要求 big-bang。** 已有 FieldType、ConfigPanel、注册表与局部编辑组件，可以逐字段替换；代码量和上述依赖链不足以证明渐进改造不可行。设计器会保存 schema，前端上线后写出的数据可能仍被旧前端读取，所以“只改前端”并不自动意味着数据零风险。证据：`frontend/src/registry/types.ts:39`、`frontend/src/pages/designer/form/FormDesigner.tsx:405`。先要求序列化输出兼容、旧 schema 不丢未知属性、回滚包可读新保存结果；代价和验收见 P1。

### A5. 流程引擎：保留抽象，收敛路径和状态守卫

**A5-1 [已复核｜兼容负担] 双运行时是有意的历史兼容，不是无理由复制。** V31 明确旧实例保留 engine_version=1；ProcessEngine 根据实例版本与 runtimeV2 是否存在分流。证据：`backend/src/main/resources/db/migration/V31__workflow_v2_foundation.sql:1`；`backend/src/main/java/com/antflow/engine/ProcessEngine.java:97`、`:126`、`:229`、`:327`。新增规则需顾及两条路径的风险为 **[推断]**；不能只因存在旧分支立即删除。动作：先清点运行中/待重提的 v1 实例，再禁止新建 v1、保留旧实例完成路径，满足退出条件后移除写路径。清点数据为 **[待实测]**，工作量归入 P3。

**A5-2 [已复核｜守卫缺口] lockPendingTask 锁了实例与任务，仅检查任务 PENDING，没有显式校验实例状态。** 证据：`backend/src/main/java/com/antflow/engine/ProcessEngine.java:851`。若出现“终态实例 + 残留待办”，这层守卫不能独立拒绝，是 **[推断]** 的非法转移风险，不是已经证明现有正常流程必然产生残留。状态仍用字符串：`backend/src/main/java/com/antflow/task/TaskEntity.java:21`、`backend/src/main/java/com/antflow/task/ProcessInstance.java:18`。动作：以实例状态、任务状态、动作和角色建立显式转移矩阵；枚举只是表达工具，关键是拒绝非法转移和并发只能推进一次。预计 3–5 工作日建立核心矩阵，归入 P3。

**A5-3 [已复核｜两套 join 判据] legacy 使用待办数量与自动化状态，V2 使用持久化分支状态。** 证据：`backend/src/main/java/com/antflow/engine/ProcessEngine.java:327`、`:334`、`:1120`；`backend/src/main/java/com/antflow/engine/WorkflowRuntimeV2.java:456`、`:470`。应随 v1 退役统一到分支状态，保留 ANY/ALL、拒绝、超时、阻塞自动化的差异，不能只把两段函数合并。

**A5-4 [已复核｜原方案需要纠正] V2 没有独立同名测试类，不等于没有行为测试。** 真库集成文件已经覆盖版本固定（`:1314`）、ALL 会签（`:1436`）、并发 ANY（`:1456`）、并行同意/拒绝竞态（`:1585`）、比例会签（`:1645`）、追回（`:1800`）、重提（`:1837`）。完整路径均为 `backend/src/test/java/com/antflow/integration/PostgresTransactionalIntegrityIntegrationTest.java`。动作：先梳理已有覆盖，补矩阵空格和残留状态，不重写一套重复测试；本轮没有重跑这些测试。

**A5-5 [已复核｜语义需命名] 撤回修改当前保持 RUNNING，进入 __rework__，不是进入终态 WITHDRAWN。** `backend/src/main/java/com/antflow/engine/ProcessEngine.java:537`、`:562`、`:570`。不能仅因为实体注释列出 WITHDRAWN 就补一次 setStatus；这会改变“可修改重提”的产品语义。动作：状态模型中显式区分撤回修改与终止，先写预期再改状态表示，归入 P3。

**[推断｜本质复杂度] 树形审批仍匹配现有设计器与导航方式，暂不引 BPMN 引擎或微服务。** 依据 `README.md:8`、`backend/src/main/java/com/antflow/engine/ProcessEngine.java:135`、`:141` 的快照树运行，以及 A2 的本地事务。若将来需要自由连线、跨流程 token 协作、补偿或标准 BPMN 交换，再重新评估，而不是以“不引 BPMN”作为永久禁令。

### A6. 权限与多组织：已有组织结构，不等于已有公司隔离

**A6-1 [已复核｜核心模型合理] 端点能力、资源授权、数据范围是正交边界。** 表单使用授权并不等于数据读范围。证据：`backend/src/main/java/com/antflow/authz/AuthorizationService.java:317`、`:409`、`:476`、`:490`；`backend/src/test/java/com/antflow/authz/AuthorizationCoverageTest.java:37`。743 行和多个谓词说明阅读成本，不能据此直接引入策略 DSL。建议把重复的裁决结果逐步表示为简单的允许/拒绝/原因码，在保持 403/404 等行为的前提下支持审计；完整工作量取决于真实重复路径，先做 2–3 工作日样例。

**A6-2 [已复核｜默认边界有条件] 按 Mapper 语句匹配是当前机制，但“不匹配”返回不注入。** `backend/src/main/java/com/antflow/authz/DataPermissionRules.java:24`、`:70`；`backend/src/main/java/com/antflow/authz/DataPermissionPolicyHandler.java:31`、`:36`。因此不能笼统说“任意受控表的新查询天然 fail-closed”。专用自助语句已用 `@InterceptorIgnore(dataPermission = "true")` 显式豁免，见 `backend/src/main/java/com/antflow/form/runtime/FormDataMapper.java:21`。建议扫描受控表读语句，要求三选一：匹配范围规则、调用已验证的专用授权 SQL、明确自助/内部豁免并有测试；不是要求每个 Mapper 机械加一个当前并不存在的 dataPermission key。后台无 principal 的合法调用要显式建模。预计 2–4 工作日建立首批覆盖断言，归入 P0。

**A6-3 [已复核｜组织事实] company 轴已有，但表单/流程/角色当前全局定义。** `backend/src/main/resources/db/migration/V1__init.sql:15`、`:23` 定义公司与部门归属；`backend/src/main/java/com/antflow/org/DepartmentController.java:28` 按公司取部门；`backend/src/main/java/com/antflow/org/UserService.java:203` 遍历多个公司。V1 的 `t_form_definition`、`t_process_definition`、`t_role` 定义分别在 `:56`、`:80`、`:31`，扫描后续迁移未发现它们新增 company_id。`backend/src/main/java/com/antflow/authz/AuthorizationService.java:738` 的授权快照仍以 user/department/admin 等表达，`:517` 的 ALL 枚举全部部门。这不是多公司授权边界。

**A6-4 [推断｜高成本决策] 公司边界必须先于多组织开放，归属政策应在 P0 拍板。** 不能只给部门 scope 补 company 条件：ALL、CUSTOM、资源授权、详情/导出、附件、草稿、异步作业和 BI 旁路都要遵守同一边界。证据锚点：`backend/src/main/java/com/antflow/authz/AuthorizationService.java:505`、`backend/src/main/java/com/antflow/form/runtime/FormDataService.java:12`、`infra/sql/bi-readonly-role.sql:87`。仅缺少显式 company 过滤不等于已证明所有部门查询会泄漏；实际越权利用需 **[待实测]** 的双公司负向测试。

建议默认政策：模板可全局共享，发布版本不可变；通过公司级使用授权决定开放范围；业务实例、填报数据和作业在创建时固定归属公司。公司管理员与企业全局管理员分开定义，角色定义是否共享与角色授予在哪家公司生效分开决定。人员调岗不改变历史单据归属。此为 **[推断｜待产品确认的推荐]**，不是直接向所有表追加 company_id；现有用户只有单个 dept_id 的建模依据是 `backend/src/main/resources/db/migration/V1__init.sql:37`，若需要一个人在多公司任职，还需单独确定成员关系模型。代价归入 P4，政策讨论 1–2 工作日并入 P0。

**[已复核｜既有约束] HIDDEN 不是保密边界，不借本轮改其语义。** `docs/DECISIONS.md:335` 已记录这一决定。更细的字段保密需要另行定义读响应契约；不能指望设计器隐藏配置承担安全隔离。

### A7. 集成与消息：优先补可靠性边界，避免重做已有设施

**A7-1 [已复核｜防护不一致] 完成回调绕过 WebhookSecurityPolicy。** `backend/src/main/java/com/antflow/notify/WebhookNotificationListener.java:68` 自建 HttpClient；TRIGGER 的 `backend/src/main/java/com/antflow/automation/WebhookClient.java:41` 调用了安全策略。但完成 URL 来自服务端 `@Value` 配置（Listener `:39`），本轮没有发现用户表单可直接控制该地址的链路。因此“策略缺口”成立，“最高优先级且普通用户可直接利用的 SSRF 漏洞”未被证实；可利用性为 **[待实测]**。

动作：P0 先核对配置入口和网络可达性，再共用出站校验与客户端；定义可信内网集成的允许范围，避免直接套用默认禁私网策略造成现有企业回调中断。核对重定向、解析后连接及超时行为；不因已经调用策略就宣布所有 SSRF 风险消失。证据：`backend/src/main/java/com/antflow/automation/WebhookSecurityPolicy.java:20`、`:34`。预计 1–3 工作日；若确认低信任方可控目标，立即按安全缺陷修复，不等 P5。

> **实施进展（2026-10-09 同日）：防护不一致已收敛；可利用性仍为 [待实测]，未升级为已证实。**
> 完成回调改为共用 `WebhookSecurityPolicy`（投递前 `validate`，含 DNS 后的地址黑名单；启动时 `validateDefinition`
> 先做无 DNS 的静态校验）。核对结论：URL 是服务端 `@Value` 配置、**不是用户可控**，且 `antflow.webhook.on-complete`
> 在全部仓库配置里都没有设置（监听器默认关闭），所以本次改动不会改断任何现有部署。
> 上面那句「定义可信内网集成的允许范围」——这一决策已拍板为**共用全局策略**，其代价（prod 下私网被无条件禁 →
> 内网完成回调在生产不可用）是明确接受的，见 `D-20261009-instance-complete-webhook-shares-outbound-policy`。
> （注：这里说的"决策项"是本文 A7 的动作项；不要与 A5 的引擎条目混淆。）
> 未做：客户端对象共用（抽公共 HttpClient bean）——对安全结论无影响，归 P5。

**A7-2 [已复核｜原方案事实错误] WeCom 已有 client、crypto 和持久化同步作业。** `backend/src/main/java/com/antflow/integration/wecom/WecomService.java:66`、`:77` 注入 WecomClient 与 WecomSecretCipher；`:219` 插入 `t_wecom_sync_job`，`:233` 在提交后投递执行器，`:335` 才外呼。它没有复用 workflow job，不等于没有入队。应把剩余的同步编排/账号映射职责拆清，复用现有设施。

**[已复核] 现有恢复动作是重启后将活动作业标为失败并提示重新同步。** WecomService `:299` 将全部 PENDING/RUNNING 标为 FAILED；`:319` 为进程内执行器。这是已复核的代码行为。若部署多个后端副本，新副本启动可能影响其他副本活动任务，是需要确认启动调用和部署方式的推断。多副本前应有 owner/lease、检查点和可重试幂等；不是为所有同步另造一套队列。预计 4–8 工作日，归入 P5。

**A7-3 [已复核｜消息路径尚未完全统一] legacy 发起通知仍走事件总线，V2 也仍有直接通知路径。** `backend/src/main/java/com/antflow/engine/ProcessEngine.java:145`、`:576`；`backend/src/main/java/com/antflow/notify/NotificationPublisher.java:25`。outbox 第十次失败变 DEAD，已有作业则有 retryFailed：`backend/src/main/java/com/antflow/notify/WorkflowOutboxDispatcher.java:100`；`backend/src/main/java/com/antflow/automation/WorkflowJobService.java:131`。本轮检索未找到 outbox 的 retryDead 入口；不等于没有人工 SQL 恢复办法。动作：补可审计的失败重放、稳定幂等键和告警；只对业务确有要求的事件定义每实例顺序。按 created_at 抢占加 SKIP LOCKED（Dispatcher `:60`）不足以承诺投递顺序。预计 3–5 工作日，归入 P5。

**A7-4 [推断｜排队范围] “外呼一律入 outbox/job”过宽。** 同步通知、批量同步等可持久化重试；OIDC 授权码交换需要当前登录请求得到结果。证据：`backend/src/main/java/com/antflow/auth/OidcService.java:98`、`:234`。后者应维持同步契约，限制超时并按协议处理失败；无需为了架构一致把登录变成后台作业。

### A8. 持久化、BI 与运维：收敛口径，不破坏已有保护

**A8-1 [已复核｜语义写入分散] t_form_data 至少有五个写入类，分布在四个包域。** FormDataService 插入（`backend/src/main/java/com/antflow/form/runtime/FormDataService.java:105`）；ProcessEngine 写入/更新（`backend/src/main/java/com/antflow/engine/ProcessEngine.java:111`、`:453`）；DefinitionVersionRepository 更新修订指针（`backend/src/main/java/com/antflow/process/DefinitionVersionRepository.java:167`）；MobileDraftService（`backend/src/main/java/com/antflow/mobile/workflow/MobileDraftService.java:51`、`:62`）；MobileWorkflowService（`backend/src/main/java/com/antflow/mobile/workflow/MobileWorkflowService.java:399`）。原“6 个模块在写”未按一致口径复现。问题仍成立：改变状态或归属含义需检查多个写入口。动作是明确谁负责状态/修订/草稿的写不变量，不为减少 Mapper 引用强行抽一个万能数据服务；分阶段迁移，预计 3–5 工作日梳理并收拢首个共享写动作。

**A8-2 [已复核｜报表漂移] V49 明确列出四处台账口径问题。** 嵌套选项遗漏、跨版本拼接、错误版本回退、多选数组比较均在 `backend/src/main/resources/db/migration/V49__ledger_row_version_and_options.sql:1` 至 `:11` 说明；Java 显示实现见 `backend/src/main/java/com/antflow/form/runtime/FormValueDisplay.java:89`、`:106`。这足以证明口径回归有实际成本，但不能断言四个 bug 全部都是 Java/SQL 复制直接导致。动作：先为版本/选项/空值/多选建立数据库对拍，再决定是否生成视图表达式；预计 3–5 工作日建立首批用例。

**A8-3 [已复核｜关键约束] SQL BI 视图不能直接删掉，应用显示文本也不能无条件替代 BI 输出。** `docs/DECISIONS.md:49`、`:51` 明确 BI 直连只读视图并绕过应用权限；`:55` 记录自由文本、附件等输出限制，`infra/sql/bi-readonly-role.sql:87` 授予五个视图。需比较结构化值解释与明确的脱敏差异，不要求所有渠道文本逐字相同。多公司 BI 若需要隔离，必须在数据库访问/视图授权层落实；仅给后端查询加 company 条件无效。全企业 BI 可见全部公司也必须明确授权，而非隐式默认。实施归入 P4/P5。

**A8-4 [已复核｜迁移分类] 14 个迁移含顶层 INSERT，不等于 14 个都混入演示 seed。** 例如 V31 的 INSERT 是历史版本回填（`backend/src/main/resources/db/migration/V31__workflow_v2_foundation.sql:38`），V40/V42 承担权限和资源模型迁移；`backend/src/main/resources/db/migration/V2__seed.sql:1` 才是明显的 seed 文件。动作：后续开发演示 seed 与运行配置分离，必须随版本演进的权限/回填数据仍留迁移。CREATE OR REPLACE VIEW 是前滚修正手段，不是应被禁用的架构错误；质量门禁应检查口径。预计 1–2 工作日制定后续分类规则，不修改历史迁移。

**A8-5 [已复核｜并发保护现状] @Version 与 FOR UPDATE 在同一表共存。** `backend/src/main/java/com/antflow/task/TaskEntity.java:23`、`backend/src/main/java/com/antflow/task/TaskMapper.java:12`；`backend/src/main/java/com/antflow/task/ProcessInstance.java:24`、`backend/src/main/java/com/antflow/task/ProcessInstanceMapper.java:67`；`backend/src/main/java/com/antflow/common/MybatisPlusConfig.java:24`。**[推断]** 行锁守护多行转移顺序，version/更新行数检查还覆盖其他更新入口；删掉任一保护需要完整调用链和竞态测试证明。建议统一“先实例后任务”的锁顺序与冲突处理，保留现有双层保护，除非后续测出冗余且证明可移除。证据：`backend/src/main/java/com/antflow/engine/ProcessEngine.java:856`、`:868`。

### A9. 测试和包边界：已有行为网，缺跨实现对账与架构门禁

**A9-1 [已复核] 桌面业务反向依赖 mobile 名称下的服务是真实的。** `backend/src/main/java/com/antflow/form/runtime/FormDataService.java:12` 至 `:14` 复用 file/link/draft；`backend/src/main/java/com/antflow/org/PickerController.java:3` 复用 mobile 组织类型。建议把真正共用的文件/草稿/组织能力迁到中立领域包，端点继续做薄适配；不按类名机械搬整个 mobile 包。预计 3–5 工作日首批迁移，归入 P5。

**A9-2 [已复核] 全仓主 Java 源码有 17 处 @Autowired(required = false)，不能都称为“假解耦”。** `backend/src/main/java/com/antflow/form/FormDefinitionService.java:30` 起有可选依赖；`backend/src/main/java/com/antflow/engine/ProcessEngine.java:81` 的 runtimeV2 缺失则确会改变新实例路径（`:126`）。应区分产品可选功能、测试便利和必需的语义依赖；后者启动即拒绝缺失，不能默默回退 legacy。

**A9-3 [已复核] 当前未配置专用包依赖与导入边界门禁，也未发现三端共读的语义 fixture 测试链路。** 查 `backend/pom.xml:1`、`frontend/biome.json:33`、`.github/workflows/ci.yml:42`、`:64`、`:95`；已有检查是常规单测/类型/构建/体积。建议 Java 用 ArchUnit，TS 从现有 Biome 能表达的限制开始；复杂别名/类型导入边界若表达不了，再补小型图检查。冻结现有违规清单，只拦新增，避免 P0 为了“全绿”被迫重构全仓；预计 2–4 工作日首批门禁。

**[推断] 4,148 行真库测试适合按流程/权限/媒体/报表分族，但应先保留数据库生命周期与隔离策略。** 依据 `backend/src/test/java/com/antflow/integration/PostgresTransactionalIntegrityIntegrationTest.java:1314`、`:2182`、`:2714` 的多个独立领域。只拆文件没有自动提速收益，也可能增加容器初始化次数；把拆分附着在新增覆盖时进行，预计 2–3 工作日，独立数据库耗时为 **[待实测]**。

### A10. 腐烂模型与复杂度排序

**[推断] “语义份数 × 平台数 × 路径数”可作风险提示，不能作为定量成本公式。** 平台数与实现份数经常重叠计数；当前没有变更工时样本证明乘法关系。“新增字段改 5–8 处”应视为待按一项真实字段任务测量的假设。可复核的代理量是类型支持差集、独立 DTO 定义数、仍可写的引擎路径数、跨实现 fixture 结果。证据来自 A3/A4/A5。

| 顺序 | 复杂度及性质 | 为什么排在这里 | 证据与置信度 |
| --- | --- | --- | --- |
| 1 | 字段语义跨端漂移；自建复杂度 | 已有确定的输入→不一致输出，不必等待规模变大 | **[已复核]** `frontend/src/registry/displayConditions.ts:200`、`mobile/src/features/forms/schema/validators.ts:114` |
| 2 | 双运行时与隐式转移；本质复杂度叠加兼容负担 | 并发、会签、重提需要精确历史语义；已有真库网，应在其上收敛 | **[推断]** `backend/src/main/java/com/antflow/engine/ProcessEngine.java:229`、`:851` |
| 3 | 公司隔离政策缺位；需求驱动的高成本决策 | 开放多组织后再修归属会涉及历史回填与授权语义 | **[推断]** `backend/src/main/resources/db/migration/V1__init.sql:23`、`:56`；`backend/src/main/java/com/antflow/authz/AuthorizationService.java:738` |
| 4 | 外呼可靠性和报表解释；本质边界加重复实现 | 分别需要可靠重试、幂等与固定历史口径；不能靠统一框架消除 | **[推断]** `backend/src/main/java/com/antflow/notify/WorkflowOutboxDispatcher.java:100`；`backend/src/main/resources/db/migration/V49__ledger_row_version_and_options.sql:1` |
| 5 | 流程×表单 1:1、两类汇报线；当前领域选择 | 应确认未来需求，不能见到两个机制就合并 | **[已复核]** `backend/src/main/resources/db/migration/V1__init.sql:82`；`backend/src/main/java/com/antflow/engine/resolver/LeaderStrategy.java:14`；`backend/src/main/java/com/antflow/engine/resolver/DirectManagerStrategy.java:18` |

**[推断] 1:1 并非必须立即突破。** 同一模板对不同公司开放可先通过授权解决；若各公司要绑定不同审批流程版本，`form_def_id UNIQUE` 就成为真实限制，应改为显式的表单版本→流程绑定关系，不能复制全套表单规避。部门主管与直属上级是不同规则，保留不同 resolver；通用条件仍可用无数据库依赖的 `backend/src/main/java/com/antflow/engine/condition/ConditionEvaluator.java:26`。

## Part B：分模块选型与演化路线

### B1. 分模块判定表

以下均为 **[推断｜推荐方案]**。复核依据回指 Part A；“可以单模块重写”是可用手段，不是必须用足的预算。

| 模块 / 侧重点 | 判定 | 技术路线与关键动作 | 必须保持的边界 / 验收 |
| --- | --- | --- | --- |
| 字段语义层 / 演化速度 | **改进：strangler 逐步替换** | 后端维护字段语义 metadata，导出版本化 spec；生成两端类型/注册数据，渲染组件仍在各应用；复用现有生成工具并先接实际契约。逐类型迁移解释规则；先 fixture，后替换，最后删被替代副本。依据 A3/A4 | 类型清单不能冒充完整语义；行内作用域、检查项、错误码和历史兼容必须有样例；SQL 暂时保留并对拍 |
| 桌面设计器 / 管理端速度 | **改进：允许局部重写装配层，否决默认 big-bang** | 保留 Umi Max/antd；逐字段把 Inspector 的完整行为迁入既有 ConfigPanel，再切注册分派；纯 schema 工具与 UI 装配分开。复用现有 `src/services/` 契约入口，避免再造平行的 `src/api/` 规范。依据 A4 | schema 读写和未知属性不丢失；旧包可读新保存结果；新建/编辑/保存/再打开/发布均通过。证实渐进方案不可行后才讨论单次替换 |
| 流程引擎 / 正确性和可审计 | **改进，不重写** | 保持本地事务与递归树；给 v1 定退役门槛；显式转移守卫和矩阵；V2 分支状态成为最终 join 权威。依据 A5 | 运行中 v1 无损结束；已发布版本与历史实例继续可读；并发转移只落一次，不删现有竞态覆盖 |
| authz / 安全与默认拒绝 | **改进** | 保留三层授权；对重复裁决提取简单结果对象和原因码；Mapper 读路径授权覆盖测试；公司边界与业务 scope 组合。依据 A6 | 不引外部 ACL 或策略 DSL；区分企业管理员/公司管理员；无上下文/未声明读路径必须被检测 |
| 移动端 / 包体积、独立发版 | **保持独立应用，改进语义契约** | 保留 Vite 与预算门禁；接生成 spec/类型，保持组件与发布独立；真正共享的后端 file/draft/org 能力移入中立包。依据 A2/A9 | 160/180 KiB 门禁持续有效；支持约定的契约兼容窗口；暂不引 workspace，不把“异构构建”误写成技术上不能用 workspace |
| 企微/OIDC/Webhook / 外部不稳定性 | **改进** | 复用 WecomClient、WecomSecretCipher、现有同步 job；抽离剩余编排/映射，补恢复机制。可靠副作用经 outbox/job；登录交换保持同步；共用出站策略。依据 A7 | URL 信任边界先核对；重放有权限、审计和稳定事件键；不创建第二套功能相同的作业平台 |
| 持久化/迁移/运维 / 可恢复、可复现 | **改进** | Flyway 只前滚；后续演示 seed 分离，权限/回填仍迁移；统一写不变量与锁顺序；ArchUnit 增量约束依赖方向。依据 A8/A9 | 不改历史文件；不机械二选一删除 @Version/FOR UPDATE；回滚依靠兼容发布和补偿迁移，不能承诺数据库随意降级 |
| 测试 / 防漂移 | **改进** | 共用 fixture + 原实现对拍 + 状态矩阵；沿现有真库网补缺口；按领域逐步拆测试族。依据 A3/A5/A9 | 不重建已覆盖的 V2 用例；正常 CI 不能长期红灯，也不能无限期忽略已知差异 |
| 分析/报表 / 口径正确 | **改进** | 先 Java/SQL 对拍固定历史口径；保留 PostgreSQL 视图与脱敏契约；再按查询负载决定生成 SQL、物化视图或读模型。依据 A8 | 公司隔离覆盖 BI 直连；版本固定、空值与多选含义一致；禁止把应用可见自由文本直接扩散给 BI |

### B2. 单一真源的可落地定义

**推荐后端维护“领域契约”，而非把 Java 私有 Set 直接翻译成 TS 后宣布完成。** A3 的错误来自算法，A4 的契约生成也尚未连接业务 API。P0 先实现一条最小纵向链路，P2 才完成全部字段覆盖。

1. **定义 spec 内容与版本。** 类型 id、值形状、配置项、空值语义、容器/行作用域、支持能力和弃用状态由后端领域 metadata 维护；React 组件、布局和编辑器交互仍由各端负责。历史表单使用的语义版本需要稳定映射，不能因为生成最新版而静默重释老数据。
2. **明确两类生成物。** HTTP DTO 从真实后端 OpenAPI 生成；字段语义 spec 生成判别联合及注册元数据。二者在后端汇合，但不是同一个输入。TS 类型不替代服务端校验；保存 DTO 的 Object/String 不能自动变成完整字段规范。
3. **同一输入和预期，独立执行。** fixture 至少包含 schema、值、版本/作用域、可见字段集合、规范错误码/字段路径、格式化结果；Java/JUnit、两端现有 Vitest 直接读取同份数据，SQL 在真库测试中比较相应列。预期来自定义清楚的领域规则，不能永远由某个当前实现现场生成。
4. **保留有意义的渠道差异。** 摘要文本和详情文本可以不同；BI 对自由文本/附件保持限制。fixture 显式给出各渠道预期，并共享底层值/版本解释；不能为追求逐字相同降低安全边界。对 table_list 逐行建立值作用域，checklist 使用自己的项结构。
5. **把生成物当发布产物管理。** 固定生成器输入和版本，CI 重生成后有差异即拒绝；生成物带 spec 版本或摘要，两端独立升级。先不改变包管理；只有纯语义实现仍反复漂移时，再评估发布无 UI 依赖的共享 TS 包。

P0 必备 fixture：条件前向引用与互指、隐藏来源、空值数值条件、明细表行内条件、固定版本的多选标签。完整字段清单和更复杂媒体校验随 P2 补齐，不能要求 P0 一次覆盖所有行为才开始修两个已知错误。

### B3. 阶段、依赖和退出条件

阶段号保留原方案命名，但**P0 拆为护栏与缺陷修复两个可审查批次**；公司政策在 P0 决定，安全核查也在 P0。表中工期仅为规划估算，可交叠部分不能直接相加当作日历承诺。

| 阶段 | 行动与顺序 | 退出条件 | 粗估代价 |
| --- | --- | --- | --- |
| **P0：护栏、已知缺陷和关键决策** | P0a：最小字段 spec/真实 API 契约链路、共享 fixture、增量架构边界、六个健康指标；同期开公司归属与可信回调目标决策。P0b：分别修复前向引用/互指与静默未知类型行为，按信任核查结果补 webhook 策略 | 能实际展示“原实现失败”；修复批次合入后用例通过。P0a 若先合入，仅可把列明的已知差异作有负责人/到期点的预期失败，不可无上限豁免。公司隔离政策与契约兼容规则明确 | **8–12 工作日**，其中缺陷修复和安全核查优先推进，不等完整工具链 |
| **P1：设计器装配层收敛** | 逐字段接通 ConfigPanel；迁移期间允许旧 switch 处理未迁移类型；先抽完整行为再删除旧分支；只迁移本轮设计器相关请求到既有 services | 迁移字段保存输出兼容；旧数据无损打开/再保存；发布校验和预览一致；可回退前端包。完成后不存在两套同时维护的字段配置面板 | **8–15 工作日**；若要一次性重写，须另有无法渐进的证据与回退演练 |
| **P2：字段语义逐类收敛** | 从标量/选择类开始，再容器/明细/检查项/媒体；每类先加 fixture、接新解释器、对拍后删旧副本；SQL先对拍保留，证实可生成后才替换手工表达式 | 三端 26 类能力都有明确结果；不支持也要显式报告；字段类型差集归零，共享用例全通过；旧 schema 的语义映射不丢 | **15–25 工作日**，按字段类别分批发版 |
| **P3：运行时与状态收敛** | 以既有真库测试为底，补状态矩阵；清点 v1；生产必需 runtimeV2 缺失时启动失败；停止新建 v1，旧实例完成后删 legacy 写分支，最终统一 join | 无可继续操作的 v1 运行/重提实例且无相关活跃 job；历史只读可用；终态实例残留 PENDING 被拒绝；ANY/ALL/拒绝竞态和 outbox 原子性通过 | **12–20 工作日**；等待旧实例自然结束的日历时间另计 |
| **P4：公司隔离实施** | 执行 P0 已决定的模板/角色/成员政策；先做存量归属清点与回填，再约束新写入；公司维度覆盖详情、列表、导出、附件、作业、BI | 两公司互不可读写的负向矩阵通过；全局管理员例外显式；调岗不改历史归属；存量无法确认归属的数据被隔离或人工确认，不默认塞给 company=1 | **15–25 工作日**；必须在多组织正式开放之前完成 |
| **P5：集成、契约与报表治理** | 企微编排/映射解耦和作业恢复，补 outbox 重放；mobile 共用能力上移；扩展真实生成契约到更多端点；完成 BI 口径回归 | 重启/重试不重复产生业务副作用；登录无异步语义改变；契约兼容旧移动端；BI 仍遵守既定权限和脱敏 | **10–20 工作日**；可与 P3/P4 的独立切片并行，webhook 风险核查已前置 P0 |

**顺序约束：** P2 涉及表单值语义时，不同时合入 P3 的流程语义改动；这样测试失败才可定位到单一变更。P1 可在 P0 最小链路与回归用例建立后开始，不必等待每一类字段的全部 fixture。P4 的决策前置，代码实施随隔离开放期限安排，不应机械等 P3 的旧实例全部自然结束。P5 触及同一契约文件时仍需串行集成。

**v1 退役不是“立刻只读”。** RUNNING 中的 `__rework__` 仍可继续操作；在存量清零或经验证迁移前要保留写路径。可只读的是已终结历史，不是仍在处理的审批。清点至少包含实例 engine_version/status/current_node_id、关联待办、延迟/失败作业及历史详情读取；本轮不执行生产查询。

### B4. 六个健康指标：现状值、目标值与统计口径

指标服务于发现漂移，不能用“文件小于 N 行”代替正确性。下面基线均来自本轮扫描；新增门禁应冻结真实基线，只禁止变差，不能把未知标成零。

| 指标 | 现状值 | 目标值 / 截止阶段 | 测量方式与证据 |
| --- | --- | --- | --- |
| **H1 字段支持差集** | 后端 26 / 桌面 21 / 移动 26；缺口 **5** | **0**，P2；P0 先消除静默无反馈 | 对照服务端 spec 与两端注册 manifest；`backend/src/main/java/com/antflow/form/FormDefinitionService.java:38`、`frontend/src/registry/formRegistry.ts:24`、`mobile/src/features/forms/schema/fieldRegistry.ts:121` |
| **H2 共用语义测试接入数** | **0/4**：两端、Java、SQL 未接同份 fixture；只读检查已复现 **2** 项失败（前向引用、互指） | **4/4** 接入，P0；已知两项修复后失败 **0**，P2 完成字段覆盖 | JUnit/Vitest/真库读取同一 fixture；集合与规范错误/文本按渠道对拍。现状锚点：`.github/workflows/ci.yml:42`、`:68`、`:91` 与 A3 |
| **H3 手写重复业务 DTO 定义数** | 独立 `FormDefinition` 声明 **5**，另一个 Detail 为派生 | 独立手写副本 **0**，P1/P5；允许基于生成类型做 Pick/扩展 | 对 TS AST 声明统计，排除生成目录；位置见 A4。不要把合法投影重复计入 |
| **H4 可写运行时实现数** | **2**（legacy/V2）；活跃 v1 实例数 **待实测** | 新实例只走 V2；最终写实现 **1**，P3 | 扫运行时分流 + 发布前数据库只读清点；`backend/src/main/java/com/antflow/engine/ProcessEngine.java:126`、`:229`；未知存量不得写作 0 |
| **H5 专用架构边界门禁** | Java 包依赖/TS 导入方向专用门禁 **0**；必需 runtimeV2 的可选注入 **1** | Java/TS **2 类**门禁就位，新增未豁免违规 **0**（P0）；runtimeV2 静默回退入口 **0**（P3） | `backend/pom.xml:1`、`frontend/biome.json:33`、`backend/src/main/java/com/antflow/engine/ProcessEngine.java:81`；现存违规首跑登记，不能假称全仓已无循环 |
| **H6 移动端体积预算** | 已实施的阈值为入口 **160 KiB**、单异步块 **180 KiB**；本轮实际产物 **未测** | 阈值不放宽，超预算产物 **0**；各阶段持续 | `.github/workflows/ci.yml:95`；`mobile/scripts/check-bundle-budget.mjs:18`。CI 附实际 gzip 数字，不用阈值冒充测量值 |

### B5. 明确不做与触发条件

| 当前不做 | 原因与重新评估条件 |
| --- | --- |
| 拆微服务、全量重写、立即换 BPMN | 当前主要问题是语义一致性，不是服务部署数量；维护本地事务与历史版本。出现可独立交付的组织/负载边界，或明确需要标准 BPMN/跨流程 token/补偿，再评估；不是因为“未来都要”就先引入 |
| 引 npm workspace、统一 Umi/Vite 构建 | 当前生成契约足以共享静态信息，迁包与锁文件没有直接修复已有 bug 的收益。**不采用 workspace 是成本判断，不是 Umi/Vite 技术不兼容，也不要求只有 pnpm 才能做。** 当存在需共同维护且频繁漂移的纯 TS 运行库时，评估独立包或 workspace；不要求顺带统一 bundler |
| 引事件溯源、通用规则引擎 | 历史表、审计、outbox 有价值，但不代表具备完整可重放的事件溯源。需按历史状态重建跨域结果时再评估事件日志完整性；第二类用户规则出现仅是复评触发点，还需证明规则表达、版本、解释与复用超过普通函数能承受的范围。现有 ConditionEvaluator 可保留 |
| 删除 SQL BI 视图、移除一种锁、把全部 INSERT 移出迁移 | 分别违反现有 BI 契约、可能削弱并发保护、可能破坏权限/历史回填。先建立对应回归证据和替代机制，沿前滚迁移演进 |
| P0 全仓清理、加全新通用框架、因为行数大就拆类 | P0 只需可工作的最小护栏和已知缺陷闭环。拆分按职责和调用边界，不按行数阈值；本轮更不执行任何生产代码整改 |

## 附录 A：原方案核验与撤回记录

此处保留审计自身的纠错，防止后续任务继续引用错误前提。证据均见对应正文条目。

| 原说法 | 本轮裁决 | 依据 |
| --- | --- | --- |
| 移动可见性仍为旧算法，字段类型 21/26/26 | **保留，已复现/计数** | A3-1/A3-2；桌面互指溢出是新增的第二个复现 |
| registry↔components 例子证明真实运行时环，因此唯一 big-bang 合理 | **撤回环的举证和必然重写结论** | A4；DeptPicker 引用纯算法文件，未回到装配注册表；ConfigPanel 已存在 |
| 桌面业务 OpenAPI 已生成，可直接复用完整字段类型 | **修正**为工具已安装、示例契约仍在，业务连接和字段 spec 需要建设 | A4-1 |
| V2 1,089 行无直接测试，因此近似无保护 | **修正**为未见独立同名测试类，但已有大量真库行为/竞态测试 | A5-4 |
| WeCom 没入 job，需要拆出 client/crypto | **撤回**；三者均已有，缺口是剩余职责和恢复策略 | A7-2 |
| 完成回调是普通用户可利用、最高优先级 SSRF | **保留防护缺口，降低利用链置信度**；先核对服务端 URL 配置的信任边界 | A7-1 |
| @Version/FOR UPDATE 必须二选一；CREATE OR REPLACE VIEW 本身是腐烂 | **撤回机械结论**；判断对象应是写不变量和口径回归 | A8-2/A8-5 |
| 6 份 FormDefinition、45 请求文件、6 个写表模块 | **按口径改写**为 5 个独立类型 + 1 个派生；45 字面调用/70 直接导入/71 含泛型调用命中文件；5 个确认写入类分布四包域 | A4/A8-1；代码检索计数不是历史工时测量 |
| 后端不校验 checklist；WeCom 有 retryDead | **不采纳**，与本轮检索不符 | `backend/src/main/java/com/antflow/form/FormDefinitionService.java:1047` 有专门校验；`:772` 有明细表校验；`backend/src/main/java/com/antflow/notify/WorkflowOutboxDispatcher.java:100` 只见 DEAD 转移，未找到 retryDead 定义 |
| 异构构建所以不能用 npm workspace；需要全面把所有外呼后台化 | **撤回必然性，保留暂不迁包的选择** | B5；npm 支持每个 workspace 执行自己的 scripts；OIDC 当前登录依赖同步交换，见 A7-4 |

## 附录 B：验证方法与未覆盖范围

### 已执行的只读验证

1. 阅读 PROJECT、HANDOFF、相关 DECISIONS 与已有权限审计体例；记录分支/HEAD、既存工作区状态，建立文件哈希基线。修改后以基线比较，区分本轮改动与用户原有改动。
2. 抽查五处指定证据：桌面 `displayConditions.ts:193`；移动 `validators.ts:114`；后端 `FormDefinitionService.java:38`；桌面 `FormRenderer.tsx:346`；迁移 `V1__init.sql:21`。路径已在 A3/A6 给全，均与当前树相符。
3. 用本机 Node 24.14.0 的 `node:module.stripTypeScriptTypes` 在内存擦除两个纯 TS 文件的类型后执行，比较前向引用输入，并捕获互指输入的 RangeError；没有安装依赖、没有复制改写待测算法、没有增加仓库测试文件。
4. 用 Git 文件清单去重后扫描实际存在的工作树文件，计物理行；以类型声明、导入、迁移建表/建视图和注入表达式的明确模式计数。检索“未发现”结论只覆盖源码/测试/配置，不包含动态配置或数据库中的 SQL。
5. 检查文档引用存在且行号有效、Part B 各模块有判定、六个指标都有现状/目标；最终确认本轮仅产生报告和 HANDOFF 三行前缀，HANDOFF 原有正文逐字节保留。

**工具选型补充依据：** 通过 Context7 查阅 npm 官方 `npm/cli` 的 `docs/lib/content/using-npm/workspaces.md`，其 workspace 命令执行各包自己的 scripts；查阅 ArchUnit 官方 `tng/archunit` 的 `docs/userguide/008_The_Library_API.adoc`，FreezingArchRule 可记录旧违规并只报告新增违规。前者用于纠正“必须统一构建才可使用 workspace”，后者支持增量接入护栏；都不替代本仓源码证据。Umi 在线文档查询未返回匹配内容，生成现状完全以本仓配置与模板契约为准。

### 未覆盖与使用限制

| 范围 | 限制 |
| --- | --- |
| 生产与安全利用链 | 未访问生产数据、当前回调配置、网络/DNS、线上镜像；未验证 SSRF 可利用性与实际受影响表单数量 |
| 完整测试和 UI | 未运行 Maven/Vitest/构建；未做浏览器或真机回归；前向引用与互指检查仅证明纯算法行为，不证明所有 UI 入口的可达性 |
| 性能与可靠性 | 未压测、未故障注入、未检查实例存量或实测包体积；不据文件行数推断吞吐，不宣称重放与恢复已满足多副本要求 |
| 全仓细节 | 移动端未逐文件审计；设计器核对了注册、Inspector、保存和指定依赖链，未覆盖全部拖拽交互；未完成全仓导入图和数据库权限清点 |
| 交付边界 | 未修 A3 的两个算法缺陷，未实施 P0–P5。用户既存的代码/迁移/文档改动保留；本轮未提交、推送或部署 |

下一步（2 分钟）：阅读 B3 的 P0 行，确认后续实施任务以“两个可见性复现 + 实际契约最小链路 + 公司归属政策”为起点；本报告本身不改变运行行为。

---

## 附录 C：实施进展（2026-10-09 同日，由另一轮实施）

报告 B3 的 P0 行已开工：**A2（共享 fixture）、P0b（两个已复现缺陷）、A1（字段类型真源，半）、A3（两侧边界门禁）、A4（六指标进 CI）** 均已实施。

**A4 的六个指标在这轮有了可执行载体**（`scripts/health-metrics.mjs` + `docs/health-metrics.baseline.json`，CI 的 `metrics` job）。
实测值与 B4 表格的差异，按"变好/变差/未测"分开记：

| 指标 | 本报告 B4 的现状值 | 本轮实测 | 说明 |
| --- | --- | --- | --- |
| H1 字段支持差集 | 缺口 5 | 缺口 5（后端 26 / 桌面 21 / 移动 26） | 相符；缺口还由 A1 的两条生成物对账测试兜底 |
| H2 共用语义测试接入数 | **0/4** | 渠道**并集 4/4**（desktop/java/mobile/sql） | 变好，但**并集不是"四端全部对拍"**：真实接入是**分维度**的——可见性 = 桌面/移动；field-types = 桌面/移动/Java；display-text = Java/SQL。指标已改成按维度记录并同时打印明细，分母来自完整声明而不是已接入数 |

> **口径澄清（2026-10-09，P2 实施后补）**：上面这句「4/4」原先只写在 H2 的现状格里，容易被读成
> "同一套语义已经四端全部对拍"。实际是**四条通道各自被某份 fixture 覆盖过**，没有一份 fixture 是四端都读的。
> 指标输出现在同时给并集与分维度明细；判定也改成"丢渠道才是变差"（原实现方向写反了：
> 新增渠道被判变差、丢失渠道反而通过）。
| H3 手写重复业务 DTO | 5 | 5 | 相符 |
| H4 可写运行时实现数 | 2，活跃 v1 实例 **待实测** | 2，活跃 v1 实例 **`null`（仍未测）** | 刻意不写 0；查法已存进脚本 |
| H5 专用架构边界门禁 | 0 类 | **Java 3 类 / TS 3 类** | 变好——A3 落地；必需 V2 的可选注入仍是 1 |
| H6 移动端体积预算 | 阈值 160/180 KiB，产物未测 | 阈值同；产物 **`null`（仍未测）** | 测量仍归 mobile 自己的预算门禁，这里只读阈值 |
面向实施的细节见 `HANDOFF.md` 顶部"最新一轮"，口径决策见 `DECISIONS.md` 的
`D-20261009-display-condition-cycles-rejected-at-save-and-publish` 与 `D-20261009-shared-field-semantics-fixtures`。

与本报告结论的差异，实施时逐一核对过，仅一处补充与一处修正：

| 项 | 本报告说法 | 实施时的核对结果 |
| --- | --- | --- |
| A3-3 严重度 | "桌面互指条件会递归溢出" | **补充**：`visibleNodeIds` 同时被渲染（`FormRenderer.tsx:333`）与提交拦截（`displayConditions.ts:249`）调用，`RangeError` 抛在 React 渲染中 → React 19 卸载整棵树 → **表单白屏、既打不开也提交不了**。且**自指 A→A 也崩**（比互指更容易构造） |
| A3-3 可达性 | "界面或导入是否能把该输入带入线上为 [待实测]" | **已复核**：界面构造不出环（`useFormDesignerStore.allowedDisplayRuleTargetIds` 已按"来源必须是 select + 目标在来源之后 + `reachesSource` 防回边"限制），**导入与直接调 API 是真实入口**，现已在后端保存/发布路径拒绝 |
| 未知类型静默返回的行号 | `FormRenderer.tsx:346` | 相符（`if (!ft) return null` 就在该行区间），已改为显式占位 |
| 移动端漂移数量 | 1 处（前向引用） | **2 处**：fixture 还抓到 `numberCompare` 未先拒空值（空值满足 `gte 0`），桌面与后端都判不成立 |

**第二轮（同日，A1/A3/A4）**：A1 只做了**前半**（字段类型真源 `FieldTypeCatalog` → 生成 `fixtures/field-semantics/field-types.json` → 两端注册表对账）；
A3 两侧门禁、A4 六指标均已落地并验过"造一条新违规就红"。

**A1 的后半（真实 OpenAPI 契约产物）已定并入 P1** —— 与 H3「手写重复业务 DTO」的目标阶段一致（本报告 `:217`）。
取法与前提已实测并记档在 `D-20261009-real-openapi-contract-generate-at-build-time-not-runtime`：活栈 `/v3/api-docs` 是
**118 KB / 163 条 `/api/` 路径**，nginx 不代理、容器里无 HTTP 客户端，故必须**构建期导出 + 提交 + CI 比对无差异**；
boot 配方的现成参照是 `PostgresTransactionalIntegrityIntegrationTest`（`storage=test` + 内存 `FileStorage` 顶掉 MinIO，
PG 走环境变量或 Testcontainer，本地 88 条用例 0 skip），**CI 现有 PG service 就够，不需要 MinIO**。
并入 P1 而不是现在做的理由：产物的消费方在 P1，先落一个没有消费方、没有门禁的快照等于再造一个化石。

**第三轮（同日，P2 第一块）**：上面那两条"仍未做"都做了。
① 桌面补齐 `radio/checkbox/search/time/switch` 五种渲染器，**H1 缺口 5 → 0**；
② 建了 `fixtures/field-semantics/display-text.json` 这个**新维度**（值 → 显示文本），Java 与 SQL 各断言自己能做的那部分，**H2 渠道并集 → 4/4**。

**三条实施时核对出来的、与本报告措辞不符的事实（都写进 DECISIONS 与本文件，别照着旧措辞理解）：**

1. **台账不是显示文本渲染器，是脱敏投影。** `V49:56` 的 `value_json`/`value_text` 有**类型白名单**，
   `text/textarea/search` 不在内 → 自由文本两列都是 NULL（真库测试 `:812-814` 早就断言了）。
   与 Java `FormValueDisplay` 的重叠面**只有"值 → 选项标签"**；Java 的 checklist 汇总、table_list 明细、附件处理，SQL 全没有。
   所以"把 SQL 接进对拍"不是接线，是**新开一个维度**。
2. **`ConfigPanel` 是死字段。** 设计器走集中式 `Inspector.renderComponentSettings()`，`grep` 证实
   `ConfigPanel` 在 `frontend/src` 里**零渲染点**——给注册字段写 `ConfigPanel` 不会让设计器出现选项编辑器。
   radio/checkbox 能配选项靠的是 Inspector 里新加的 `case`。
3. **H2 的比较方向原来写反了**（新增渠道被判变差、丢失渠道反而通过）。这与"更新基线"是两件事：
   改方向 + 加 `--self-test`，不是把基线改一改盖过去。

**明确记录、本轮不修的差异（避免"缺口归零"被读成"语义全一致"）**：
`FormValueDisplay.java:241` 的 `selectText` **只对 `multi_select` 拼标签**；`checkbox` 是数组值，
走到 `type != multi_select && value.isArray()` 就 `return null`，回退成通用值展示。SQL 侧同样只处理 `multi_select`。
**所以桌面补出 checkbox 渲染器，并不会顺带修好 checkbox 在台账/导出里的标签显示**——那是**待收敛的现状，不是目标契约**。
